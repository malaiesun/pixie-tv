#!/usr/bin/env bash
# Wait for the TV after a reboot, then report: which ADB paths came back, whether the debloat held, health checks.
# Usage: scripts/after-reboot.sh [timeout-seconds]
export MSYS_NO_PATHCONV=1
_env="$(cd "$(dirname "$0")/.." && pwd)/local.env"; [ -f "$_env" ] && . "$_env"
IP="${TV_IP:?set TV_IP in local.env (cp local.env.example local.env)}"; LIMIT="${1:-240}"; t=0
port_open() { timeout 2 bash -c "echo > /dev/tcp/$IP/$1" 2>/dev/null; }
until port_open 8008; do t=$((t+5)); [ $t -ge $LIMIT ] && { echo "TV not reachable at $IP after ${LIMIT}s (wrong Wi-Fi or new IP?)"; exit 1; }; sleep 5; done
echo "TV on network after ~${t}s"; sleep 20   # let adbd and mDNS settle
port_open 5555 && echo "adb 5555:          OPEN" || echo "adb 5555:          closed"
TLS="$(adb mdns services | awk '/_adb-tls-connect/{print $3; exit}')"
echo "wireless debugging: ${TLS:-off (not advertised)}"
if [ -n "$TLS" ]; then adb connect "$TLS" >/dev/null 2>&1; export TV="$TLS"
elif port_open 5555; then adb connect "$IP:5555" >/dev/null 2>&1; export TV="$IP:5555"
else echo "no ADB path: turn on Wireless debugging on the TV"; exit 2; fi
. "$(dirname "$0")/lib.sh"
echo "uptime: $(a cat /proc/uptime | awk '{print int($1)}')s  boot_completed=$(a getprop sys.boot_completed)  adb_wifi_enabled=$(a settings get global adb_wifi_enabled)"
EXPECT="$(grep -vE '^\s*(#|$)' "$ROOT/debloat/43gu3q.txt" | sed 's/#.*//; s/[[:space:]]//g' | sort)"
GOT="$(a pm list packages -d | sed 's/^package://' | sort)"
MISSING="$(comm -23 <(echo "$EXPECT") <(echo "$GOT"))"
[ -z "$MISSING" ] && echo "debloat held: all $(echo "$EXPECT" | wc -l) still disabled" || { echo "RE-ENABLED by firmware:"; echo "$MISSING"; }
bash "$ROOT/scripts/checks.sh" 06-after-reboot
