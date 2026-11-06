#!/usr/bin/env bash
# End-to-end test of the PIXIE scripts in Home Assistant: run each, check what the TV shows (via adb), go Home.
. "$(dirname "$0")/../scripts/lib.sh" >/dev/null
HA="$HA_URL"; H="Authorization: Bearer $(tr -d '\r\n' < "$ROOT/ha/.token")"
run() { curl -s -m 30 -H "$H" -H 'Content-Type: application/json' -X POST "$HA/api/services/script/$1" -d "$2" -o /dev/null -w '%{http_code}'; }
pass=0; fail=0
t() { # t <script> <json> <expected package> <label>
  code=$(run "$1" "$2"); wait_s 9; f="$(front)"
  if [[ "$f" == "$3"/* ]]; then echo "ok   $4"; pass=$((pass+1)); else echo "FAIL $4 (HTTP $code, front: ${f:-?})"; fail=$((fail+1)); fi
  run pixie_key '{"key":"HOME"}' >/dev/null; wait_s 3
}
t pixie_hdmi_1 '{}' com.smartdevice.livetv "HDMI 1"
t pixie_hdmi_2 '{}' com.smartdevice.livetv "HDMI 2"
t pixie_hdmi_3 '{}' com.smartdevice.livetv "HDMI 3"
t pixie_av     '{}' com.smartdevice.livetv "AV"
while IFS='|' read -r label app pkg; do
  t pixie_open_app "{\"app\":\"$app\"}" "$pkg" "app: $label"
done <<'APPS'
YouTube|https://www.youtube.com|com.google.android.youtube.tv
Netflix|https://www.netflix.com/browse|com.netflix.ninja
JioHotstar|https://www.hotstar.com/in|in.startv.hotstar
APPS
f="$(front)"; [[ "$f" == com.google.android.apps.tv.launcherx/* ]] && echo "ok   key HOME" || echo "FAIL key HOME (front: $f)"
echo "== $pass passed, $fail failed"
