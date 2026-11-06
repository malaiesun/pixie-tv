#!/usr/bin/env bash
# Records the README media from the TV into docs/media/: per-theme Home screenshots, app-opening GIFs
# (Pixie's move + the theme's opening effect, nothing actually opens), roaming GIFs, the theme-change
# transition, the boot greeting, and the overlay panels. Takes ~4 minutes; the TV ends on the theme it had.
# Needs: adb, ffmpeg, Python + Pillow (outfit sheet). Usage: scripts/record-media.sh
. "$(dirname "$0")/lib.sh"
OUT="$ROOT/docs/media"; WORK="$ROOT/shots/media-work"
mkdir -p "$OUT" "$WORK"
tv() { python "$ROOT/pc/tvctl.py" "$@" >/dev/null 2>&1; }
pause() { sleep "$1"; }

# GIF with a per-clip palette: gif <in.mp4> <out.gif> <start s> <length s> [width]
gif() {
  ffmpeg -loglevel error -y -ss "$3" -t "$4" -i "$1" -vf \
    "fps=15,scale=${5:-640}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=192:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
    "$2" && echo "  $(basename "$2")  $(du -k "$2" | cut -f1) KB"
}
# Screen recording; the trigger runs 1.5 s in (screenrecord needs ~1 s to start): rec <name> <seconds> <cmd...>
rec() {
  local name=$1 secs=$2; shift 2
  adb -s "$TV" shell "screenrecord --time-limit $secs --size 1280x720 /sdcard/rec.mp4" </dev/null >/dev/null 2>&1 &
  local pid=$!
  pause 1.5; "$@"
  wait $pid
  adb -s "$TV" pull /sdcard/rec.mp4 "$(cd "$WORK" && pwd -W)/$name.mp4" >/dev/null 2>&1
}
png() {   # png <name>: screenshot scaled to 1280x720
  shot "media-work/$1-full" >/dev/null 2>&1
  ffmpeg -loglevel error -y -i "$WORK/$1-full.png" -vf scale=1280:-1:flags=lanczos "$OUT/$1.png" && echo "  $1.png"
}
focus_netflix() { tv key BACK; pause 1; tv key RIGHT; pause 2.5; }   # Back on Home = top row

was=$(adb -s "$TV" shell content read --uri content://dev.malaiesun.home.config/config.json </dev/null 2>/dev/null \
      | tr -d '\r' | sed -n 's/.*"theme_preset": *"\([a-z]*\)".*/\1/p' | head -1)
echo "== theme now: ${was:-?} (restored at the end)"

python "$ROOT/scripts/pixie_sheet.py" 8 >/dev/null && echo "  pixie-outfits.png"

for t in retro comic neon hero aurora minimal; do
  echo "== $t"
  tv theme "$t"; pause 5                      # transition + "Ta-da!"
  focus_netflix
  png "home-$t"
  rec "open-$t" 6 tv anim demo
  gif "$WORK/open-$t.mp4" "$OUT/open-$t.gif" 1.0 4.2
  case $t in comic|neon|hero|aurora)
    rec "roam-$t" 7 true
    gif "$WORK/roam-$t.mp4" "$OUT/roam-$t.gif" 1.0 5.5 ;;
  esac
done

echo "== theme change"
tv theme retro; pause 5; focus_netflix
rec theme-switch 10 sh -c "python '$ROOT/pc/tvctl.py' theme neon >/dev/null 2>&1; sleep 3.6; python '$ROOT/pc/tvctl.py' theme comic >/dev/null 2>&1"
gif "$WORK/theme-switch.mp4" "$OUT/theme-switch.gif" 1.0 7.5

echo "== greeting + panels (retro)"
tv theme retro; pause 5; focus_netflix
rec hello 8 tv mascot hello
gif "$WORK/hello.mp4" "$OUT/hello.gif" 1.0 6.0
tv panel quick;  pause 1.5; png panel-quick;  tv key BACK; pause 1
tv panel ha;     pause 2.5; png panel-ha;     tv key BACK; pause 1
tv panel themes; pause 1.5; png panel-themes; tv key BACK; pause 1
tv panel voice;  pause 1.5; png panel-voice;  tv key BACK; pause 1
tv popup "Someone is at the door. Show the camera?" -b Show -b Ignore; pause 1.5; png popup; tv key BACK; pause 1
tv notify "Dinner's ready!"; pause 1.2; png notify; pause 4
tv volume +1; pause 0.6; png volume-retro; tv volume -1; pause 2
tv theme neon; pause 5; focus_netflix
tv volume +1; pause 0.6; png volume-neon; tv volume -1; pause 2
tv panel quick; pause 1.5; png panel-quick-neon; tv key BACK; pause 1

tv theme "${was:-retro}"
echo "== done: $OUT"
ls -la "$OUT" | awk '{print $5, $9}' | tail -n +2
