#!/usr/bin/env bash
# Print on-screen text elements with their bounds (uiautomator), or tap one by exact text.
# Usage: scripts/ui.sh            list
#        scripts/ui.sh tap "Text"  tap the first element whose text matches exactly
. "$(dirname "$0")/lib.sh" >/dev/null
a uiautomator dump /data/local/tmp/ui.xml >/dev/null
XML="$(a cat /data/local/tmp/ui.xml)"
a rm -f /data/local/tmp/ui.xml >/dev/null
if [ "${1:-}" = tap ]; then
  XY="$(printf '%s' "$XML" | python -I -c "
import sys, re, html
want = sys.argv[1]
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', sys.stdin.read()):
    if html.unescape(m.group(1)) == want:
        x1, y1, x2, y2 = map(int, m.groups()[1:]); print((x1 + x2) // 2, (y1 + y2) // 2); break" "$2")"
  [ -n "$XY" ] || { echo "not on screen: $2"; exit 1; }
  a input tap $XY; echo "tapped $2 at $XY"
else
  printf '%s' "$XML" | python -I -c "
import sys, re, html
for m in re.finditer(r'text=\"([^\"]+)\"[^>]*checked=\"(true|false)\"[^>]*bounds=\"(\[[0-9,]+\]\[[0-9,]+\])\"', sys.stdin.read()):
    print('  ', html.unescape(m.group(1)), ('[on]' if m.group(2) == 'true' else ''), m.group(3))"
fi
