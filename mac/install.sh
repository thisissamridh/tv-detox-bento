#!/bin/bash
# Installs the Mac side: the TV Remote app (starts at login, Control+Option+Space) and the weekly
# tune-up (checked hourly). Run again after changing either.
#   sh mac/install.sh <TV_IP>     install, or update
#   sh mac/install.sh --remove    remove both
set -e
cd "$(dirname "$0")"
if [ "${1:-}" != "--remove" ]; then
  [ -n "${1:-}" ] || { echo "Usage: sh mac/install.sh <TV_IP>" >&2; exit 1; }
  case "$1" in *:*) TVADDR=$1 ;; *) TVADDR=$1:5555 ;; esac
  mkdir -p "$HOME/.config/tv-detox"
  echo "$TVADDR" > "$HOME/.config/tv-detox/tv"
fi
APP="$HOME/Applications/TV Remote.app"
AGENTS="$HOME/Library/LaunchAgents"
REMOTE=com.ansub.tvremote
TUNE=com.ansub.tvtune

for id in $REMOTE $TUNE; do launchctl bootout "gui/$(id -u)/$id" 2>/dev/null || true; done
if [ "${1:-}" = "--remove" ]; then
  rm -rf "$APP" "$AGENTS/$REMOTE.plist" "$AGENTS/$TUNE.plist" "$HOME/.tv-tune/" "$HOME/.config/tv-detox"
  echo "Removed TV Remote and the weekly tune-up."; exit 0
fi

swiftc -O -swift-version 5 TVRemote.swift -o TVRemote
mkdir -p "$APP/Contents/MacOS" "$HOME/.tv-tune" "$AGENTS"
cp TVRemote "$APP/Contents/MacOS/TVRemote"
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>TV Remote</string>
  <key>CFBundleIdentifier</key><string>$REMOTE</string>
  <key>CFBundleExecutable</key><string>TVRemote</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSUIElement</key><true/>
</dict></plist>
EOF
codesign --force --sign - "$APP" >/dev/null 2>&1 || true
cp weekly-tune.sh "$HOME/.tv-tune/weekly-tune.sh"
chmod +x "$HOME/.tv-tune/weekly-tune.sh"

cat > "$AGENTS/$REMOTE.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$REMOTE</string>
  <key>ProgramArguments</key><array><string>$APP/Contents/MacOS/TVRemote</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
EOF
cat > "$AGENTS/$TUNE.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$TUNE</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$HOME/.tv-tune/weekly-tune.sh</string></array>
  <key>StartInterval</key><integer>3600</integer>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
</dict></plist>
EOF
for id in $REMOTE $TUNE; do launchctl bootstrap "gui/$(id -u)" "$AGENTS/$id.plist"; done
echo "Installed. Press Control+Option+Space for the remote. Tune-up log: ~/Library/Logs/tv-tune.log"
