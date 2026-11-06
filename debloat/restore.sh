#!/usr/bin/env bash
# Re-enable every package in the debloat list (brings the TV back to stock for Phase 1).
# Factory-disabled packages are not in the list, so they stay as the factory left them.
# Usage: debloat/restore.sh [--dry-run]
. "$(dirname "$0")/../scripts/lib.sh"
LIST="$ROOT/debloat/43gu3q.txt"
DRY="${1:-}"
PKGS="$(grep -vE '^\s*(#|$)' "$LIST" | sed 's/#.*//; s/[[:space:]]//g')"
echo "== restore on $TV ${DRY:+(dry run)}"
for p in $PKGS; do
  if [ -n "$DRY" ]; then echo "adb shell pm enable --user 0 $p"; continue; fi
  a pm enable --user 0 "$p" >/dev/null
  if a pm list packages -e | grep -qx "package:$p"; then echo "enabled   $p"; log_change "enable $p (restore)" "adb shell pm disable-user --user 0 $p"
  else echo "FAILED    $p"; fi
done

# ---- Phase 3: give Home back to Google TV ----
# Stock launcher first, then setupwraith, then point HOME at launcherx. PIXIE Home stays installed
# (uninstall it with `adb shell pm uninstall dev.malaiesun.home` if wanted).
run() { if [ -n "$DRY" ]; then echo "adb shell $*"; else a "$@" >/dev/null && echo "ok        $*" || echo "FAILED    $*"; fi; }
run pm enable --user 0 com.google.android.apps.tv.launcherx
run pm enable --user 0 com.google.android.tungsten.setupwraith
run cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
[ -n "$DRY" ] || log_change "restore Home to launcherx (enable launcherx + setupwraith)" "re-run the Phase 3 handover"

# ---- PIXIE agent (remote shortcuts, overlays, LAN API) off ----
run settings delete secure enabled_accessibility_services
run pm revoke dev.malaiesun.home android.permission.RECORD_AUDIO
run cmd deviceidle whitelist -dev.malaiesun.home

# ---- Settings changed along the way ----
run settings put global animator_duration_scale 1.0
run settings put global send_action_app_error 1
run settings put secure send_action_app_error 1
run settings put global adb_enabled 0            # LAST: USB debugging off (closes adb 5555 after reboot)

# Not undoable by script: removed user 10 (leftover profile), uninstalled FLauncher (Play Store),
# forgotten duplicate 2.4 GHz Wi-Fi network (re-join in Settings). Optional extras, by hand:
#   adb shell pm uninstall dev.malaiesun.home     (PIXIE Home + agent)
#   adb shell pm uninstall org.smarttube.stable   (SmartTube)
#   delete %APPDATA%\Microsoft\Windows\SendTo\PIXIE TV.cmd   (Explorer Send to entry)
