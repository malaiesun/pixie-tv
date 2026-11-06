#!/usr/bin/env bash
# Build PIXIE Home and install it on the TV. Does NOT make it Home (that is the Phase 3 handover).
# Usage: launcher/build-install.sh [--config-only]
#   --config-only   just push launcher/config/pixie.json (HA token from ha/.token, HA URL from local.env) and reload
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../scripts/lib.sh" >/dev/null
PKG=dev.malaiesun.home


# Android 14 blocks adb from /sdcard/Android/data, so config goes through the app's ConfigProvider.
URI=content://$PKG.config

push_config() {
  local tmp; tmp="$(mktemp)"
  python -I - "$ROOT/launcher/config/pixie.json" "$ROOT/ha/.token" "$(cygpath -w "$tmp" 2>/dev/null || echo "$tmp")" "$HA_URL" <<'EOF'
import sys, json, pathlib
cfg = json.loads(pathlib.Path(sys.argv[1]).read_text())
cfg["home_assistant"]["token"] = pathlib.Path(sys.argv[2]).read_text().strip()
if cfg["home_assistant"].get("url", "").startswith("__"): cfg["home_assistant"]["url"] = sys.argv[4]
pathlib.Path(sys.argv[3]).write_text(json.dumps(cfg, indent=2))
EOF
  adb -s "$TV" shell content write --uri "$URI/config.json" < "$tmp"
  rm -f "$tmp"
  adb -s "$TV" shell content write --uri "$URI/rules.json" < "$ROOT/launcher/config/rules.json"
  echo "config + rules pushed via $URI"
}

# One-time agent setup (idempotent): mic for HA Assist, Android 14 restricted-settings unlock for a
# sideloaded accessibility service, the service itself, and no battery restrictions.
setup_agent() {
  local svc="$PKG/$PKG.PixieService" cur
  a pm grant $PKG android.permission.RECORD_AUDIO >/dev/null || true
  a appops set $PKG ACCESS_RESTRICTED_SETTINGS allow >/dev/null || true
  a cmd deviceidle whitelist +$PKG >/dev/null || true
  cur="$(a settings get secure enabled_accessibility_services)"
  case "$cur" in
    *PixieService*) ;;
    null|"") a settings put secure enabled_accessibility_services "$svc" ;;
    *) a settings put secure enabled_accessibility_services "$cur:$svc" ;;
  esac
  a settings put secure accessibility_enabled 1
  grep -q "enable PIXIE agent" "$ROOT/changes.log" 2>/dev/null ||     log_change "enable PIXIE agent (accessibility $svc, RECORD_AUDIO, restricted-settings appop, deviceidle whitelist)"       "adb shell settings put secure enabled_accessibility_services \"\" ; adb shell pm revoke $PKG android.permission.RECORD_AUDIO"
}

# Copy the TV's current config (favorites / theme changed on the TV) back into the repo, token masked.
if [ "${1:-}" = "--pull-config" ]; then
  adb -s "$TV" exec-out content read --uri "$URI/config.json" | python -I -c "
import sys, json
c = json.load(sys.stdin)
c.setdefault('home_assistant', {})['token'] = '__FROM_ha/.token__'
c['home_assistant']['url'] = '__FROM_local.env__'
open(sys.argv[1], 'w', encoding='utf-8').write(json.dumps(c, indent=2) + '\n')
print('pulled TV config into', sys.argv[1])" "$(cygpath -w "$ROOT/launcher/config/pixie.json" 2>/dev/null || echo "$ROOT/launcher/config/pixie.json")"
  exit 0
fi

if [ "${1:-}" != "--config-only" ]; then
  (cd "$HERE" && ./gradlew -q assembleRelease) || { echo "BUILD FAILED - nothing installed"; exit 1; }
  APK="$HERE/app/build/outputs/apk/release/app-release.apk"
  ls -l "$APK"
  adb -s "$TV" push "$(cygpath -m "$APK" 2>/dev/null || echo "$APK")" /data/local/tmp/pixie-home.apk >/dev/null
  a pm install -r /data/local/tmp/pixie-home.apk
  a rm -f /data/local/tmp/pixie-home.apk
  a appops set $PKG GET_USAGE_STATS allow   # Recent row
  grep -q "install $PKG" "$ROOT/changes.log" 2>/dev/null || \
    log_change "install $PKG (PIXIE Home launcher, not Home yet)" "adb shell pm uninstall $PKG"
  # First start creates the app's files dir so the config can be pushed into it.
  a monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
  wait_s 3
fi
push_config
[ "${1:-}" = "--config-only" ] || setup_agent
a input keyevent KEYCODE_HOME >/dev/null
