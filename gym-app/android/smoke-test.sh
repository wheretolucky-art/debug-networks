#!/usr/bin/env bash
# Installs the debuggable test APK on the emulator, opens it, and lets cdp-test.mjs drive the page
# (made-up sample data only) while it takes screenshots and records what happened.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$HERE/.." && pwd)/out"
PKG=com.debugnetworks.gym
LOG="$OUT/smoke.log"
adb wait-for-device
echo "== wait for the emulator to finish starting" > "$LOG"
for i in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
sleep 45 # a fresh emulator starts many of its own apps after boot; launching during that can fail for lack of memory
echo "== install" >> "$LOG"
adb install -r "$OUT/gym-app-test.apk" >> "$LOG" 2>&1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >> "$LOG" 2>&1 || true
for attempt in 1 2 3; do
  echo "== start, attempt $attempt" >> "$LOG"
  adb shell am start -W -n "$PKG/.MainActivity" >> "$LOG" 2>&1
  sleep 15
  if adb shell pidof "$PKG" > /dev/null 2>&1 && adb shell cat /proc/net/unix | grep -q webview_devtools_remote; then
    echo "app is up" >> "$LOG"
    break
  fi
  echo "app is not up yet, starting it again" >> "$LOG"
  adb shell am force-stop "$PKG" >> "$LOG" 2>&1
  sleep 20
done
adb exec-out screencap -p > "$OUT/screen-0-start.png"
timeout 300 node "$HERE/cdp-test.mjs" "$OUT" >> "$LOG" 2>&1 || echo "cdp-test exited with $?" >> "$LOG"
adb shell pidof "$PKG" >> "$LOG" 2>&1 && echo "app still running" >> "$LOG"
adb logcat -d -s chromium:V AndroidRuntime:E ActivityManager:W > "$OUT/logcat.txt" 2>&1 || true
exit 0
