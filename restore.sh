#!/bin/sh
# Rebuilds a setup saved by backup.sh: installs the home screen, turns the same apps off, puts the
# settings back and restores your tiles. Install your apps from the Play Store first (apps.txt
# lists them), so their tiles have something to open.
#
#   sh restore.sh <TV_IP> <backup folder>
set -e
cd "$(dirname "$0")"
[ -n "$1" ] && [ -d "$2" ] || { echo "Usage: sh restore.sh <TV_IP> <backup folder>" >&2; exit 1; }
case "$1" in *.*.*.*:*) TV=$1 ;; *.*.*.*) TV=$1:5555 ;; *) TV=$1 ;; esac
DIR=$2
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV." >&2; exit 1; }
sh_tv() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

missing=$(for p in $(cat "$DIR/apps.txt" 2>/dev/null); do sh_tv pm list packages "$p" | grep -qx "package:$p" || echo "$p"; done)
[ -z "$missing" ] || { echo "Not installed yet (tiles for these will be skipped):"; echo "$missing" | sed 's/^/  /'; }

# 1. The home screen, its permissions and the extras (the settings below then override them).
[ -f out/tvhome.apk ] || sh build.sh
YES=1 YES_SETUP=1 sh install.sh "$TV"
YES=1 sh tune.sh "$TV"

# 2. The same apps off. Saved for undo.sh, like install.sh does.
for p in $(cat "$DIR/disabled.txt"); do
  sh_tv pm list packages "$p" | grep -qx "package:$p" || continue
  sh_tv pm list packages -d "$p" | grep -qx "package:$p" && continue
  sh_tv "echo $p >> /data/local/tmp/tvhome-disabled.txt"
  sh_tv pm disable-user --user 0 "$p" >/dev/null 2>&1 && echo "off  $p"
done

# 3. Settings exactly as they were.
while read -r ns key val; do
  [ -n "$key" ] && sh_tv settings put "$ns" "$key" "$val" && echo "set  $key"
done < "$DIR/settings.txt"

# 4. Tiles and home screen settings, through the same extras install.sh uses.
set -- --es stamp "$(date +%s)"
for k in apps quick tmdb ps5; do
  v=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2],''))" "$DIR/config.json" "$k" 2>/dev/null)
  [ -n "$v" ] && set -- "$@" --es "$k" "\"$v\""
done
sh_tv am start -n com.ansub.tvhome/.MainActivity "$@" >/dev/null
echo "Saving the change on the TV, this takes 15 seconds…"; sleep 15
sh_tv input keyevent KEYCODE_HOME
echo "Restored from $DIR."
