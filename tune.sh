#!/bin/sh
# The extras, run after install.sh. Each step asks first (YES=1 says yes to all of them).
#
#   sh tune.sh <TV_IP>                         the usual extras
#   sh tune.sh <TV_IP> debloat/sony-bravia.txt also turn off the apps in that list
#   COMPILE=1 sh tune.sh <TV_IP>               also pre-compile your main apps (slow, ~20 min)
#
# Everything here is undone by undo.sh.
set -e
cd "$(dirname "$0")"
[ -n "$1" ] || { echo "Usage: sh tune.sh <TV_IP> [debloat-list.txt]" >&2; exit 1; }
case "$1" in *.*.*.*:*) TV=$1 ;; *.*.*.*) TV=$1:5555 ;; *) TV=$1 ;; esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV." >&2; exit 1; }
sh_tv() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }
ask() { [ -n "$YES" ] && return 0; printf '%s [y/N] ' "$1"; read a </dev/tty || a=n; case "$a" in y|Y|yes) return 0 ;; esac; return 1; }
ME=com.ansub.tvhome
sh_tv pm list packages $ME | grep -qx "package:$ME" || { echo "Run install.sh first." >&2; exit 1; }

# Needed by the sidebar's ad blocker switch and by "order tiles by use". Always on: they only
# let the home screen read and change those two things.
sh_tv pm grant $ME android.permission.WRITE_SECURE_SETTINGS && echo "Ad blocker switch: allowed"
sh_tv appops set $ME GET_USAGE_STATS allow && echo "Tiles ordered by use: allowed"

if ask "Make the remote's settings and app buttons work without Google TV's home (recommended)?"; then
  cur=$(sh_tv settings get secure enabled_accessibility_services)
  case "$cur" in *$ME/*) ;; null|"") sh_tv settings put secure enabled_accessibility_services $ME/$ME.KeyService ;;
    *) sh_tv settings put secure enabled_accessibility_services "$cur:$ME/$ME.KeyService" ;; esac
  sh_tv settings put secure accessibility_enabled 1
  echo "  Settings button opens the sidebar; Netflix, Prime and YouTube buttons work."
fi

if ask "Use the photo-of-the-day clock as the screensaver?"; then
  sh_tv settings put secure screensaver_enabled 1
  sh_tv settings put secure screensaver_components $ME/.ClockDream
  echo "  Screensaver set."
fi

if ask "Block ads on the whole TV with AdGuard's free DNS?"; then
  sh_tv settings put global private_dns_specifier dns.adguard-dns.com
  sh_tv settings put global private_dns_mode hostname
  echo "  Ad blocker on. Switch it off any time in the settings sidebar."
fi

if ask "Make animations twice as fast?"; then
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do sh_tv settings put global $k 0.5; done
  echo "  Animations at 0.5x."
fi

# Apps from a list are turned off (not deleted), and saved for undo.sh like install.sh does.
LIST="$2"
if [ -n "$LIST" ] && [ -f "$LIST" ] && ask "Turn off the apps in $LIST?"; then
  for p in $(grep -v '^[[:space:]]*#' "$LIST" | awk '{print $1}' | grep .); do
    sh_tv pm list packages "$p" | grep -qx "package:$p" || continue
    sh_tv "echo $p >> /data/local/tmp/tvhome-disabled.txt"
    sh_tv pm disable-user --user 0 "$p" >/dev/null 2>&1 && echo "  off  $p"
  done
  echo "Saving the change on the TV, this takes 15 seconds…"; sleep 15
fi

if [ -n "$COMPILE" ]; then
  echo "Pre-compiling your installed TV apps. This is slow on TVs; leave it running."
  for p in $(sh_tv pm list packages -3 | sed 's/package://') com.google.android.youtube.tv com.google.android.webview; do
    printf '  %s: ' "$p"; sh_tv cmd package compile -m speed -f "$p" | tail -1
  done
fi
echo "Done."
