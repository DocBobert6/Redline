#!/usr/bin/env bash
# B30.1: the minified release APK must launch. Debug instrumented tests never run R8, so a startup crash
# from shrinking (e.g. an initializer whose classes R8 renamed) slipped through to v0.3.0-alpha.1.
set -euo pipefail
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
PKG=com.redline.camera
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
if adb logcat -d -b crash | grep -q "$PKG"; then
  echo "Release APK crashed on launch:"; adb logcat -d -b crash; exit 1
fi
adb shell pidof "$PKG" >/dev/null || { echo "Release APK process not running after launch"; adb logcat -d | tail -200; exit 1; }
echo "Release APK launched and is running."
