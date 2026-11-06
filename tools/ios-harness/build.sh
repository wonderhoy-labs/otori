#!/bin/zsh
# Builds the measurement harness and installs it on an iPhone 15 Pro simulator.
# Usage: build.sh <simulator-udid>
set -e
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
HERE=${0:A:h}
UDID=${1:?usage: build.sh <simulator-udid>}
xcodebuild -project "$HERE/IslandRef.xcodeproj" -scheme IslandRef -sdk iphonesimulator \
  -destination "id=$UDID" -derivedDataPath "$HERE/build" build 2>&1 | grep -E "error:|BUILD |warning: unable" | tail -20
xcrun simctl terminate "$UDID" com.wonderhoylabs.islandref 2>/dev/null || true
xcrun simctl install "$UDID" "$HERE/build/Build/Products/Debug-iphonesimulator/IslandRef.app"
echo installed
