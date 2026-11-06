#!/bin/sh
# Gets a Pixel 10 Pro emulator ready for tools/e2e.py: matches the phone's display mode, installs the app and the
# test player, and turns on Otori's two permissions. Emulators only, because this changes security settings.
# Usage: tools/prepare_emulator.sh emulator-5554
set -e
SERIAL=${1:?usage: prepare_emulator.sh emulator-5554}
case "$SERIAL" in
  emulator-*) ;;
  *) echo "Refusing to change security settings on $SERIAL. On a real phone, use the switches in the app." >&2; exit 1 ;;
esac
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP=com.wonderhoylabs.otori

adb -s "$SERIAL" shell wm size 1080x2410
adb -s "$SERIAL" shell wm density 420
adb -s "$SERIAL" install -r "$ROOT"/app/build/outputs/apk/release/app-release.apk
adb -s "$SERIAL" install -r -g "$ROOT"/test-player/build/outputs/apk/debug/test-player-debug.apk
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "$APP/$APP.IslandService"
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1
adb -s "$SERIAL" shell cmd notification allow_listener "$APP/$APP.IslandNotificationListener"
echo "Ready. Run: python3 tools/e2e.py --serial $SERIAL"
