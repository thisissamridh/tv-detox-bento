#!/bin/sh
# Saves this TV's setup to a folder, so restore.sh can rebuild it after a factory reset or on
# another Android TV. Nothing private: no passwords, logins or app data.
#
#   sh backup.sh <TV_IP> [folder]      default folder: backups/<date>
#
# The folder holds:
#   config.json   the home screen's tiles and settings
#   disabled.txt  every app that's turned off
#   settings.txt  the TV settings tune.sh changes, as they are now
#   apps.txt      the apps you installed (for reference; reinstall them from the Play Store)
set -e
cd "$(dirname "$0")"
[ -n "$1" ] || { echo "Usage: sh backup.sh <TV_IP> [folder]" >&2; exit 1; }
case "$1" in *.*.*.*:*) TV=$1 ;; *.*.*.*) TV=$1:5555 ;; *) TV=$1 ;; esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV." >&2; exit 1; }
sh_tv() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }
DIR=${2:-backups/$(date +%Y-%m-%d)}
mkdir -p "$DIR"

adb -s "$TV" pull /sdcard/Android/data/com.ansub.tvhome/files/config.json "$DIR/config.json" >/dev/null 2>&1 \
  || echo '{}' > "$DIR/config.json"
sh_tv pm list packages -d | sed 's/package://' | sort > "$DIR/disabled.txt"
sh_tv pm list packages -3 | sed 's/package://' | grep -v '^com.ansub.tvhome$' | sort > "$DIR/apps.txt"
: > "$DIR/settings.txt"
for k in "global private_dns_mode" "global private_dns_specifier" "global window_animation_scale" \
         "global transition_animation_scale" "global animator_duration_scale" \
         "secure screensaver_enabled" "secure screensaver_components" "secure enabled_accessibility_services" \
         "system screen_off_timeout"; do
  v=$(sh_tv settings get $k)
  [ "$v" = null ] || echo "$k $v" >> "$DIR/settings.txt"
done
echo "Saved to $DIR: $(wc -l < "$DIR/disabled.txt" | tr -d ' ') apps off, $(wc -l < "$DIR/settings.txt" | tr -d ' ') settings, your tiles."
