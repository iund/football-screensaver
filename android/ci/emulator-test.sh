#!/bin/bash
# CI-only: installs the APK on the emulator, screenshots MainActivity and the live wallpaper preview
# (the real wallpaper engine), and fails on any crash. Usage: emulator-test.sh <apk> <outdir>
set -u
APK=$1 OUT=$2
mkdir -p "$OUT"
adb install -r "$APK"
adb logcat -c
adb shell am start -n com.iund.football/.MainActivity
sleep 25
adb exec-out screencap -p > "$OUT/activity.png"
adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
  --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT com.iund.football/.FootballWallpaperService
sleep 20
adb exec-out screencap -p > "$OUT/wallpaper-preview.png"
adb logcat -d > "$OUT/logcat.txt"
grep -E "Football|AndroidRuntime" "$OUT/logcat.txt" | tail -40
! grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"
