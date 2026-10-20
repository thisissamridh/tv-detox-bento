#!/bin/bash
# Weekly TV tune-up, run by launchd every hour from this Mac. It only does anything once 7 days have
# passed since the last tune-up, and only when the TV is idle (asleep, or showing the screensaver),
# so it never interrupts a show. Each run is logged to ~/Library/Logs/tv-tune.log.
#
#   1. Clears the cache of every app (Android's own "free up space"; logins and settings stay).
#   2. Closes everything running in the background.
#   3. Restarts the TV, but only if the screensaver is on: from standby it stays asleep instead,
#      so it never lights up in the middle of the night.

TV=$(cat "$HOME/.config/tv-detox/tv" 2>/dev/null) || { echo "No TV set. Run mac/install.sh <TV_IP>." >&2; exit 1; }
STATE="$HOME/.tv-tune-last"
LOG="$HOME/Library/Logs/tv-tune.log"
ADB=$(command -v adb || echo /opt/homebrew/bin/adb)
WEEK=$((7 * 24 * 3600))

log() { echo "$(date '+%Y-%m-%d %H:%M')  $*" >> "$LOG"; }
tv() { timeout 20 "$ADB" -s "$TV" shell "$@" 2>/dev/null | tr -d '\r'; }

last=$(cat "$STATE" 2>/dev/null || echo 0)
now=$(date +%s)
[ "${1:-}" = "--now" ] || [ $((now - last)) -ge $WEEK ] || exit 0

timeout 15 "$ADB" connect "$TV" >/dev/null 2>&1
[ "$(timeout 10 "$ADB" -s "$TV" get-state 2>/dev/null)" = device ] || { log "skipped: TV not reachable"; exit 0; }

state=$(tv dumpsys power | grep -oE 'mWakefulness=[A-Za-z]+' | cut -d= -f2)
if [ "$state" = Awake ] && [ "${1:-}" != "--now" ]; then
  log "skipped: TV is in use"; exit 0
fi

before=$(tv cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}')
tv pm trim-caches 999G >/dev/null
tv am kill-all >/dev/null
after=$(tv cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}')
echo "$now" > "$STATE"

if [ "$state" = Dreaming ]; then
  log "tuned: caches cleared, free RAM ${before} to ${after} MB, restarting (screensaver was on)"
  timeout 20 "$ADB" -s "$TV" reboot >/dev/null 2>&1
else
  log "tuned: caches cleared, free RAM ${before} to ${after} MB, no restart (TV was $state)"
fi
