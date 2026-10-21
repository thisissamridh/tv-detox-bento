#!/bin/sh
# Sets the home screen tiles from a file (default tiles.txt; see tiles.example.txt).
#   sh tiles.sh <TV_IP> [file]
set -e
cd "$(dirname "$0")"
[ -n "$1" ] || { echo "Usage: sh tiles.sh <TV_IP> [file]" >&2; exit 1; }
case "$1" in *.*.*.*:*) TV=$1 ;; *.*.*.*) TV=$1:5555 ;; *) TV=$1 ;; esac
FILE=${2:-tiles.txt}
[ -f "$FILE" ] || { echo "No $FILE. Copy tiles.example.txt to tiles.txt and edit it." >&2; exit 1; }
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV." >&2; exit 1; }
apps=$(grep -v '^[[:space:]]*#' "$FILE" | tr -d ' \t\r' | grep . | paste -sd, -)
adb -s "$TV" shell am start -n com.ansub.tvhome/.MainActivity --es stamp "$(date +%s)" --es apps "\"$apps\"" >/dev/null
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Tiles set: $(echo "$apps" | tr ',' '\n' | wc -l | tr -d ' ')"
