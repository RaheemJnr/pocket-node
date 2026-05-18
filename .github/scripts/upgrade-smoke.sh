#!/usr/bin/env bash
# Upgrade-smoke harness: install prev → seed wallet → install PR → assert.
#
# Lives as a real file because reactivecircus/android-emulator-runner@v2
# splits its inline `script:` into per-line `sh -c` invocations under dash,
# which kills any multi-line shell construct (functions, traps, if/fi
# spanning lines, etc.). Invoking this file with `bash` keeps everything in
# one process so the trap, if-blocks, and pipefail behave normally.

set -euo pipefail

capture_state() {
  echo "::group::Capturing failure state"
  # Post-instrumentation state (likely launcher; instrumentation cleanup
  # tore down the app before this trap fired). Useful as a sanity check.
  adb shell screencap -p /sdcard/post.png 2>/dev/null || true
  adb pull /sdcard/post.png post.png 2>/dev/null || true
  adb shell uiautomator dump /sdcard/dump.xml 2>/dev/null || true
  adb pull /sdcard/dump.xml ui-dump.xml 2>/dev/null || true
  # The TestWatcher rule inside UpgradeSmokeTest grabs screenshot + UI
  # dump on failure — pull both so post-mortem sees the actual state
  # at the moment the assertion fired.
  adb pull /sdcard/fail-seedFreshWallet.png fail-seedFreshWallet.png 2>/dev/null || true
  adb pull /sdcard/fail-seedFreshWallet.xml fail-seedFreshWallet.xml 2>/dev/null || true
  adb pull /sdcard/fail-assertHomeAfterUpgrade.png fail-assertHomeAfterUpgrade.png 2>/dev/null || true
  adb pull /sdcard/fail-assertHomeAfterUpgrade.xml fail-assertHomeAfterUpgrade.xml 2>/dev/null || true
  adb logcat -d > logcat-post.txt 2>/dev/null || true
  echo "::endgroup::"
}
trap capture_state EXIT

adb wait-for-device
adb shell input keyevent 82 || true

echo "::group::Install prev APK"
adb install -r prev/app-debug.apk
echo "::endgroup::"

echo "::group::Install androidTest APK"
adb install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
echo "::endgroup::"

echo "::group::Seed wallet on prev APK"
adb shell am instrument -w \
  -e class com.rjnr.pocketnode.UpgradeSmokeTest#seedFreshWallet \
  com.rjnr.pocketnode.test/androidx.test.runner.AndroidJUnitRunner | tee seed.log
if ! grep -q 'OK (1 test)' seed.log; then
  echo "Prev APK seed failed; main is broken, not this PR"
  exit 1
fi
echo "::endgroup::"

echo "::group::Install PR APK over prev (data preserved)"
adb shell am force-stop com.rjnr.pocketnode
adb logcat -c
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
echo "::endgroup::"

echo "::group::Assert Home renders post-upgrade"
adb shell am instrument -w \
  -e class com.rjnr.pocketnode.UpgradeSmokeTest#assertHomeAfterUpgrade \
  com.rjnr.pocketnode.test/androidx.test.runner.AndroidJUnitRunner | tee post.log
if ! grep -q 'OK (1 test)' post.log; then
  echo "Post-upgrade Home assertion failed"
  exit 1
fi
echo "::endgroup::"

echo "::group::Logcat scan"
adb logcat -d > logcat-post.txt
if grep -qE 'FATAL EXCEPTION|AndroidRuntime.*FATAL' logcat-post.txt; then
  echo "FATAL detected in logcat:"
  grep -E 'FATAL EXCEPTION|AndroidRuntime.*FATAL' logcat-post.txt
  exit 1
fi
echo "::endgroup::"

echo "::group::Process liveness"
pid=$(adb shell pidof com.rjnr.pocketnode 2>/dev/null | tr -d '\r' || true)
if [ -z "$pid" ]; then
  echo "com.rjnr.pocketnode not running after smoke"
  exit 1
fi
echo "pid=$pid"
echo "::endgroup::"
