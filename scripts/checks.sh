#!/usr/bin/env bash
# Run the standard health checks: Home, YouTube, HDMI switching, RAM, screenshot.
# Usage: scripts/checks.sh <label>
. "$(dirname "$0")/lib.sh"
L="${1:-check}"
echo "== checks: $L"
check_home
echo "     $(shot "$L-home")"
check_youtube
check_hdmi
check_home
echo "ram  $(ram)"
