#!/usr/bin/env bash
# Phase 0 recon: READ-ONLY. Collects device state into recon/. Changes nothing on the TV
# (the only write is a temporary screenshot in /data/local/tmp, deleted right after pull).
# Usage: TV=<ip>:5555 scripts/recon.sh
set -u
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite /data/... into C:/Program Files/Git/...
TV="${TV:?set TV=<ip>:port}"
OUT="$(dirname "$0")/../recon"
mkdir -p "$OUT"
a() { adb -s "$TV" shell "$@" 2>&1 | tr -d '\r'; }

a getprop                                   > "$OUT/getprop.txt"
a cat /proc/cpuinfo                         > "$OUT/cpuinfo.txt"
a cat /proc/meminfo                         > "$OUT/proc-meminfo.txt"
a pm list packages -f -s                    > "$OUT/packages-system.txt"
a pm list packages -f -3                    > "$OUT/packages-thirdparty.txt"
a pm list packages -d                       > "$OUT/packages-disabled.txt"
a pm list packages -e                       > "$OUT/packages-enabled.txt"
a pm list packages -f -u                    > "$OUT/packages-all-incl-uninstalled.txt"
a pm list users                             > "$OUT/users.txt"
{ echo "## resolve-activity HOME"; a cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
  echo; echo "## query-activities HOME"; a cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
} > "$OUT/home.txt"
a dumpsys meminfo                           > "$OUT/dumpsys-meminfo.txt"
a dumpsys activity processes                > "$OUT/dumpsys-activity-processes.txt"
a df -h                                     > "$OUT/df.txt"
{ a wm size; a wm density; echo "display_density_forced=$(a settings get secure display_density_forced)"; } > "$OUT/display.txt"
a dumpsys tv_input                          > "$OUT/dumpsys-tv_input.txt"
a dumpsys accessibility                     > "$OUT/dumpsys-accessibility.txt"
a dumpsys activity activities | grep -iE 'ResumedActivity|mFocusedApp' > "$OUT/foreground.txt"
a "dumpsys wifi | grep -o 'cname=[^ ]*' | sort | uniq -c" > "$OUT/wifi-owner.txt"
for ns in global secure system; do a settings list $ns > "$OUT/settings-$ns.txt"; done
a dumpsys deviceidle whitelist              > "$OUT/deviceidle-whitelist.txt"

# Screenshot: exec-out is corrupted on SW6H builds, so screencap to a temp file, pull, delete.
a screencap -p /data/local/tmp/recon.png
adb -s "$TV" pull /data/local/tmp/recon.png "$OUT/screenshot.png" >/dev/null 2>&1
a rm -f /data/local/tmp/recon.png
echo "done: $(ls "$OUT" | wc -l) files in $OUT"
