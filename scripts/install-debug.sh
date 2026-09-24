#!/usr/bin/env bash
# 编译 debug APK 并一键安装到手机 —— 兼容入口。
#
# 实现（真机优先的设备选择、JAVA_HOME 守卫、APK 选取）都在 ./scripts/android install-debug，
# 这里只做转调，避免同一套逻辑维护两份、慢慢漂移。
#
# 用法：./scripts/install-debug.sh [设备序列号]
# 未指定序列号时会安装到全部已连接真机。
# 不带参数时自动选择已连接的真机（模拟器会被忽略）。序列号可以用 adb devices -l 查看。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/android" install-debug "$@"
