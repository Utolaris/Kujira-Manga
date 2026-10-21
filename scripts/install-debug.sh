#!/usr/bin/env bash
# 编译 debug APK 并一键安装到手机 —— 兼容入口。
#
# 实现（设备代号、并发安装、失败汇总、JAVA_HOME 守卫、APK 选取）都在 ./scripts/android install-debug，
# 这里只做转调，避免同一套逻辑维护两份、慢慢漂移。
#
# 用法：./scripts/install-debug.sh [设备序列号]
# 未指定序列号时会并发安装到全部已连接真机，全部结束后统一报告失败。
# 忽略模拟器，检测时显示设备代号；序列号可用 ./scripts/android devices 查看。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/android" install-debug "$@"
