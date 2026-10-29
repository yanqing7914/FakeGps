#!/usr/bin/env bash
# FakeGps 回归测试：编译 → JVM 单元测试 → 启动模拟器 → 设备上的回归测试。
#
# 用法：
#   ./scripts/regression.sh            # 全部跑
#   ./scripts/regression.sh --unit     # 只跑 JVM 单元测试（不需要模拟器）
#   AVD=test34 ./scripts/regression.sh # 指定模拟器
#
# 已连接真机时会优先在真机上跑设备测试（需要先在手机上打开 USB 调试）。
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD="${AVD:-test34}"
PKG="com.freelocation.app"
STARTED_EMULATOR=0

log() { printf '\n==> %s\n' "$*"; }

cleanup() {
  if [[ "$STARTED_EMULATOR" == "1" ]]; then
    log "关闭本脚本启动的模拟器"
    "$ADB" emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

log "JVM 单元测试 + 编译"
./gradlew --no-daemon -q testDebugUnitTest assembleDebug assembleDebugAndroidTest
echo "单元测试结果："
for f in app/build/test-results/testDebugUnitTest/*.xml; do
  grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"
done

if [[ "${1:-}" == "--unit" ]]; then
  log "只跑单元测试，完成"
  exit 0
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
  [[ -x "$EMULATOR" ]] || { echo "没有已连接设备，也找不到模拟器：$EMULATOR" >&2; exit 1; }
  log "启动模拟器 $AVD"
  nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-snapshot \
    -gpu swiftshader_indirect -no-boot-anim >/tmp/fakegps-emulator.log 2>&1 &
  STARTED_EMULATOR=1
  "$ADB" wait-for-device
fi

log "等待设备启动完成"
for _ in $(seq 1 120); do
  [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2
done
"$ADB" shell getprop ro.product.model | tr -d '\r'

log "安装 App 和测试包"
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

log "授予权限"
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell appops set "$PKG" android:mock_location allow
"$ADB" shell am force-stop "$PKG"

log "设备上的回归测试"
OUT=$("$ADB" shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r')
echo "$OUT" | grep -E "INSTRUMENTATION_STATUS: (test|class)=|INSTRUMENTATION_STATUS_CODE: -2|^OK|FAILURES|Tests run" || true
echo "$OUT" | grep -A15 "INSTRUMENTATION_STATUS: stack=" | head -40 || true

if echo "$OUT" | grep -qE "^OK \([0-9]+ tests?\)"; then
  log "全部通过：$(echo "$OUT" | grep -E '^OK')"
else
  log "有失败用例"
  echo "$OUT" | tail -20
  exit 1
fi
