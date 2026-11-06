#!/usr/bin/env bash
# Call the PIXIE agent API with the token from pc/.pixie.json.
# Usage: scripts/api.sh <path> [json-body]      e.g. scripts/api.sh action '{"panel":"ha"}'
#        scripts/api.sh GET state
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$HERE/pc/.pixie.json"
read -r HOST PORT TOKEN < <(python -I -c "import json,sys;c=json.load(open(sys.argv[1]));print(c['host'],c['port'],c['token'])" "$(cygpath -w "$CFG" 2>/dev/null || echo "$CFG")")
METHOD=POST
if [ "$1" = "GET" ]; then METHOD=GET; shift; fi
PATH_PART="$1"
BODY="${2:-}"
[ -n "$BODY" ] || BODY='{}'
if [ "$METHOD" = GET ]; then
  curl -s -m 30 -H "Authorization: Bearer $TOKEN" "http://$HOST:$PORT/api/$PATH_PART"
else
  curl -s -m 30 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X POST "http://$HOST:$PORT/api/$PATH_PART" -d "$BODY"
fi
echo
