#!/usr/bin/env bash
# Shared helpers for talking to the TV. Source it: . scripts/lib.sh
# Picks the Wireless debugging (TLS) transport if connected, since 5555 drops mid-session on SW6H;
# falls back to 5555. Override with TV=<ip>:<port>.
export MSYS_NO_PATHCONV=1            # Git Bash on Windows: don't rewrite /data/... into C:/...
_env="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/local.env"
[ -f "$_env" ] && . "$_env"            # TV_IP, HA_URL, HA_ADB_PLAYER (git-ignored; see local.env.example)
HA_URL="${HA_URL:-http://homeassistant.local:8123}"
if [ -z "${TV_IP:-}" ] && [ -z "${TV:-}" ]; then
  echo "Set TV_IP in local.env (cp local.env.example local.env)" >&2; exit 1
fi
if [ -z "${TV:-}" ]; then
  TV="$(adb devices | awk -v ip="$TV_IP" '$2=="device" && index($1, ip":")==1 && $1 !~ /:5555$/ {print $1; exit}')"
  [ -n "$TV" ] || TV="$TV_IP:5555"
fi
# pwd -W gives J:/... on Git Bash, which adb.exe can write to with path conversion off; plain pwd elsewhere.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
LOG="$ROOT/changes.log"

# </dev/null: adb shell otherwise swallows the caller's stdin (breaks `while read` loops).
# Returns adb's exit status (not grep's), so `a settings put ... && next` works even with no output.
a()     { local out rc; out="$(adb -s "$TV" shell "$@" </dev/null 2>&1)"; rc=$?
          [ -n "$out" ] && printf '%s\n' "$out" | tr -d '\r' | grep -v '^Init wrapper sys mutex'; return $rc; }
wait_s(){ adb -s "$TV" shell sleep "$1" </dev/null >/dev/null 2>&1; }   # sleep on the TV side
ram()   { a cat /proc/meminfo | awk '/MemAvailable/{printf "%d MB available\n", $2/1024}'; }
front() { a dumpsys activity activities | grep -m1 -E 'ResumedActivity' | grep -oE 'u0 [^ }]+' | cut -c4-; }

# shot <name>: screencap to a temp file on the TV, pull, delete (exec-out is corrupted on SW6H).
shot() {
  mkdir -p "$ROOT/shots"
  a screencap -p /data/local/tmp/_s.png >/dev/null
  adb -s "$TV" pull /data/local/tmp/_s.png "$ROOT/shots/$1.png" >/dev/null 2>&1
  a rm -f /data/local/tmp/_s.png >/dev/null
  echo "shots/$1.png"
}

home() { a input keyevent KEYCODE_HOME >/dev/null; wait_s 3; }

# check_youtube: launch YouTube, confirm it is in front, go Home.
check_youtube() {
  a monkey -p com.google.android.youtube.tv -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
  wait_s 8
  local f; f="$(front)"
  case "$f" in com.google.android.youtube.tv/*) echo "ok   youtube: $f";; *) echo "FAIL youtube: front is $f";; esac
  home
}

# check_hdmi: switch to HDMI 1 through the TV input framework, confirm Live TV app took it, go Home.
check_hdmi() {
  a am start -a android.intent.action.VIEW -d 'content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW5' >/dev/null
  wait_s 6
  local f; f="$(front)"
  case "$f" in com.smartdevice.livetv/*) echo "ok   hdmi1: $f";; *) echo "FAIL hdmi1: front is $f";; esac
  home
}

# check_home: Home key lands on a launcher.
check_home() {
  home
  local f; f="$(front)"
  case "$f" in com.google.android.apps.tv.launcherx/*) echo "ok   home: $f";; *) echo "WARN home: front is $f";; esac
}

log_change() { printf '%s | %-45s | undo: %s\n' "$(date '+%F %T')" "$1" "$2" >> "$LOG"; }
