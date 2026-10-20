#!/bin/bash
# Cold-start time of each installed TV app in ms: closed first, opened, timed by Android, then
# back home. Run before and after a change to see if it helped.
#   bash tools/launch-times.sh <TV_IP> [package ...]
[ -n "$1" ] || { echo "Usage: bash tools/launch-times.sh <TV_IP> [package ...]" >&2; exit 1; }
case "$1" in *:*) export ANDROID_SERIAL=$1 ;; *) export ANDROID_SERIAL=$1:5555 ;; esac
shift
adb connect $ANDROID_SERIAL >/dev/null 2>&1
apps=${*:-$(adb shell pm list packages -3 | tr -d '\r' | sed 's/package://' | grep -v com.ansub.tvhome)}
for p in $apps; do
  c=$(timeout 10 adb shell cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1 | tr -d '\r')
  case "$c" in */*) ;; *) continue ;; esac
  timeout 10 adb shell am force-stop $p; sleep 2
  t=$(timeout 40 adb shell am start -W -n "$c" 2>/dev/null | grep -E '^TotalTime' | awk '{print $2}')
  echo "$p ${t:-?}"
  sleep 3; timeout 10 adb shell input keyevent HOME; sleep 2
done
