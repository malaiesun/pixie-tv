#!/usr/bin/env bash
# Disable one batch from a debloat list, with safety checks. Logs each change + undo to changes.log.
# Usage: scripts/disable.sh <list> <batch-number> [--dry-run]
. "$(dirname "$0")/lib.sh"
LIST="${1:?list file}"; BATCH="${2:?batch number}"; DRY="${3:-}"

# Packages in "## batch N" section, comments stripped.
PKGS="$(awk -v n="$BATCH" '/^## batch /{on=($3==n":")} on && !/^#/ {sub(/#.*/,""); gsub(/[ \t]/,""); if($0!="") print}' "$LIST")"
[ -n "$PKGS" ] || { echo "no packages in batch $BATCH"; exit 1; }

# Core Android / TV middleware (same rule as tv-vibehack tools/disable.sh) + this TV's must-keep list.
CORE_RE='^(android|android\.ext\.(services|shared)|com\.android\.sdksandbox|com\.android\.location\.fused|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|com\.android\.tv\.settings|com\.(google\.)?android\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)|com\.google\.android\.overlay\.modules.*|com\.android\.providers\..*|com\.cltv\.mal|com\.mediatek\.(tis|tv\.service|tv\.agent))$'
CORE_OK='^com\.android\.providers\.(calendar|contacts|userdictionary|blockednumber)$'
KEEP_RE='^(com\.google\.android\.apps\.tv\.launcherx|com\.google\.android\.tungsten\.setupwraith|com\.mediatek\.tvinput|com\.mediatek\.android\.tv\.globalkeyoverlay|com\.smartdevice\.livetv.*|com\.smartdevice\.(bleconnectservice|duplebluetooth|factory|dailyshortcut)|mediatek\.factorymenu\.ui|com\.google\.android\.tv\.remote\.service|com\.google\.android\.inputmethod\.latin|com\.android\.vending|com\.google\.android\.(gms|gsf)|com\.dolby\.android\.audio\.service)$'
BAD="$(printf '%s\n' "$PKGS" | grep -E "$CORE_RE|$KEEP_RE" | grep -vE "$CORE_OK")"
[ -z "$BAD" ] || { echo "REFUSING batch $BATCH: core/must-keep packages:"; printf '  %s\n' $BAD; exit 2; }

INSTALLED="$(a pm list packages | sed 's/^package://')"
DISABLED="$(a pm list packages -d | sed 's/^package://')"
echo "== batch $BATCH on $TV ${DRY:+(dry run)}"
for p in $PKGS; do
  if ! printf '%s\n' "$INSTALLED" | grep -qx "$p"; then echo "absent    $p"; continue; fi
  if printf '%s\n' "$DISABLED" | grep -qx "$p"; then echo "already   $p (left alone, not logged)"; continue; fi
  if [ -n "$DRY" ]; then echo "would     $p"; continue; fi
  a pm disable-user --user 0 "$p" >/dev/null          # one pm per shell call
  if a pm list packages -d | grep -qx "package:$p"; then
    echo "disabled  $p"; log_change "disable $p" "adb shell pm enable --user 0 $p"
  else
    echo "FAILED    $p"
  fi
done
