#!/usr/bin/env bash
# Records Pixie's app-opening move in each theme (no app is opened) and builds one contact sheet per theme
# in shots/: move_<theme>.png. Usage: scripts/test-pixie.sh [theme ...]   (default: all six, ends on the first)
. "$(dirname "$0")/lib.sh"
SHOTS="$ROOT/shots"; mkdir -p "$SHOTS"
THEMES="${*:-retro minimal comic neon hero aurora}"
tv() { python "$ROOT/pc/tvctl.py" "$@" >/dev/null 2>&1; }
for t in $THEMES; do
  tv theme "$t"
  wait_s 3                                    # Home rebuilds, Pixie settles (comic teleports in)
  adb -s "$TV" shell "screenrecord --time-limit 5 --size 1280x720 /sdcard/m.mp4 & sleep 0.6; wait" </dev/null >/dev/null 2>&1 &
  rec=$!
  sleep 1.6; tv mascot demo
  wait $rec
  adb -s "$TV" pull /sdcard/m.mp4 "$SHOTS/move_$t.mp4" >/dev/null 2>&1
  ffmpeg -loglevel error -y -ss 1.3 -i "$SHOTS/move_$t.mp4" -vf "fps=12,scale=480:-1,tile=4x4" -frames:v 1 "$SHOTS/move_$t.png" \
    && echo "move_$t.png"
done
set -- $THEMES; tv theme "$1"
