#!/usr/bin/env bash
# Opens every PIXIE overlay, scrolls it, and screenshots each step into shots/menu_<n>_<name>.png,
# then builds shots/menus.png (contact sheet). Keys go through the agent's API, so they reach the panels.
# Usage: scripts/test-menus.sh [theme]
. "$(dirname "$0")/lib.sh"
SHOTS="$ROOT/shots"; mkdir -p "$SHOTS"; rm -f "$SHOTS"/menu_*.png
tv() { python "$ROOT/pc/tvctl.py" "$@" >/dev/null 2>&1; }
n=0
snap() { n=$((n + 1)); wait_s "${2:-1.2}"; local f; f=$(printf "menu_%02d_%s" "$n" "$1"); shot "$f" >/dev/null 2>&1; echo "$f"; }
keys() { for k in "$@"; do tv key "$k"; done; }

[ -n "${1:-}" ] && { tv theme "$1"; wait_s 3; }
tv key HOME; wait_s 1
snap home
tv panel quick;  snap quick;        keys DOWN DOWN DOWN;  snap quick_bottom;  keys BACK
tv panel ha;     snap ha;           keys DOWN DOWN DOWN;  snap ha_bottom;     keys BACK
tv panel apps;   snap apps;         keys DOWN DOWN DOWN DOWN; snap apps_bottom; keys BACK
tv panel voice;  snap voice;        keys BACK
tv panel themes; snap themes;       keys DOWN DOWN;       snap themes_bottom
keys UP DPAD_CENTER;                snap theme_pick 1.4      # picks Retro again: one Pixie should say "Ta-da!"
tv popup "Someone is at the door. Show the camera?" -b Show -b Ignore; snap popup; keys BACK
tv notify "Dinner's ready!";        snap notify 1.0
tv volume +1;                       snap volume 0.5; tv volume -1
tv action '{"toast":{"text":"Hello from the test"}}'; snap toast_home 0.8
tv panel quick; tv action '{"toast":{"text":"Toast over a panel"}}'; snap toast_panel 0.8; keys BACK
wait_s 1; snap home_after
cd "$SHOTS" && ffmpeg -loglevel error -y -pattern_type sequence -i menu_%02d_%*.png -vf "scale=480:-1,tile=4x5" -frames:v 1 menus.png 2>/dev/null \
  || python - <<'PY'
import glob, subprocess
fs = sorted(glob.glob("menu_*.png"))
args = []
for f in fs: args += ["-i", f]
n = len(fs); cols = 4; rows = (n + cols - 1) // cols
flt = "".join(f"[{i}]scale=480:270[s{i}];" for i in range(n))
pad = cols * rows - n
for i in range(pad): flt += f"color=black:480x270[p{i}];"
ins = "".join(f"[s{i}]" for i in range(n)) + "".join(f"[p{i}]" for i in range(pad))
flt += f"{ins}xstack=inputs={cols*rows}:layout=" + "|".join(f"{(i%cols)*480}_{(i//cols)*270}" for i in range(cols*rows))
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args, "-filter_complex", flt, "-frames:v", "1", "menus.png"], check=True)
PY
echo "sheet: shots/menus.png"
