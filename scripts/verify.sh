#!/usr/bin/env bash
# Phase 6: check the whole softmod is still in place. Read-only. Exits 1 if anything failed.
# Usage: scripts/verify.sh
. "$(dirname "$0")/lib.sh" >/dev/null
fail=0
ok()   { printf 'ok    %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*"; fail=1; }
warn() { printf 'warn  %s\n' "$*"; }

if ! a echo up | grep -q up; then echo "FAIL  no adb connection to $TV"; exit 1; fi
ok "adb $TV (uptime $(a cat /proc/uptime | awk '{print int($1/60)}') min)"

# Home
home="$(a cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
[ "$home" = "dev.malaiesun.home/.HomeActivity" ] && ok "Home resolves to PIXIE Home" || bad "Home resolves to $home"
dis="$(a pm list packages -d | sed 's/^package://')"
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  printf '%s\n' "$dis" | grep -qx "$p" && ok "$p disabled" || bad "$p is enabled again"
done

# Debloat list
want="$(grep -vE '^\s*(#|$)' "$ROOT/debloat/43gu3q.txt" | sed 's/#.*//; s/[[:space:]]//g')"
missing="$(comm -23 <(printf '%s\n' "$want" | sort) <(printf '%s\n' "$dis" | sort))"
n="$(printf '%s\n' "$want" | wc -l)"
[ -z "$missing" ] && ok "debloat: all $n packages disabled" || bad "debloat: re-enabled -> $(echo $missing)"

# Agent
svc="$(a settings get secure enabled_accessibility_services)"
case "$svc" in *dev.malaiesun.home.PixieService*) ok "PIXIE agent enabled";; *) bad "PIXIE agent not enabled (accessibility)";; esac
a dumpsys accessibility | grep -q 'label=PIXIE agent' && ok "PIXIE agent bound" || bad "PIXIE agent not bound"
info="$(curl -s -m 5 "http://${TV%%:*}:8765/api/info")"
case "$info" in *'"name":"PIXIE"'*) ok "agent API reachable: $info";; *) bad "agent API not reachable on :8765";; esac
if [ -f "$ROOT/pc/.pixie.json" ]; then
  st="$(bash "$ROOT/scripts/api.sh" GET state)"
  case "$st" in *'"ok":true'*) ok "tvctl token works ($(printf '%s' "$st" | python -I -c "import sys,json;s=json.load(sys.stdin);print('theme',s['theme'],'| rules',s['rules'],'| volume',s['volume'])"))";; *) bad "tvctl token rejected";; esac
fi
a dumpsys deviceidle whitelist | grep -q dev.malaiesun.home && ok "agent exempt from battery optimisation" || warn "agent not on deviceidle whitelist"

# Home Assistant
if [ -f "$ROOT/ha/.token" ]; then
  code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(tr -d '\r\n' < "$ROOT/ha/.token")" "$HA_URL/api/")"
  [ "$code" = 200 ] && ok "Home Assistant reachable" || warn "Home Assistant answered HTTP $code"
fi

# Network / ADB persistence
owner="$(a dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u | tr '\n' ' ')"
case "$owner" in *setupwraith*) bad "a saved Wi-Fi network is still owned by setupwraith";; *) ok "Wi-Fi networks owned by: $owner";; esac
[ "$(a settings get global adb_enabled)" = 1 ] && ok "USB debugging on (adb 5555 comes back after reboot)" || warn "USB debugging off: adb will be gone after a reboot"
dns="$(a settings get global private_dns_mode)"
[ "$dns" = hostname ] && ok "Private DNS: $(a settings get global private_dns_specifier)" || warn "Private DNS not set (mode: $dns)"

# Speed
printf 'info  animations: window %s, transition %s, animator %s\n' "$(a settings get global window_animation_scale)" \
  "$(a settings get global transition_animation_scale)" "$(a settings get global animator_duration_scale)"
printf 'info  RAM: %s\n' "$(ram)"
printf 'info  PIXIE Home process: %s\n' "$(a dumpsys meminfo dev.malaiesun.home | grep -m1 'TOTAL PSS' | awk '{print $3/1024 " MB PSS"}')"

[ $fail = 0 ] && echo "== all checks passed" || echo "== SOME CHECKS FAILED"
exit $fail
