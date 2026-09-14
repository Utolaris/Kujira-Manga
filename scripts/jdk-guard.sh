#!/usr/bin/env bash
# 供 scripts/android 与 scripts/run-instrumented-tests.sh `source` 的 Gradle JDK 守卫。
# 这是共享库，不是入口脚本，不要直接执行。
#
# 项目标准 JDK：Eclipse Temurin 21（brew cask 安装）
#   brew install --cask temurin@21    # 需要管理员密码，装在 /Library/Java/JavaVirtualMachines
#
# 为什么必须锁 OpenJDK 21：
#   AGP 的 JdkImageTransform 会 jlink 到 android core-for-system-modules，而 GraalVM 的
#   java.base 仍要求 jdk.internal.vm.ci，变换必然失败。gradle.properties 顶部与
#   AGENTS.md 记录了同一件事。
#
# 为什么按绝对路径解析、不查 PATH 也不查 java_home：
#   本机 PATH 上的 java 与 `java_home -V` 都可能落到 GraalVM（GraalVM 装在
#   /Library/Java/JavaVirtualMachines 且是 java_home 的候选之一）。信任它们就等于
#   必然踩上面那个坑。唯一例外是用户已显式设好 JAVA_HOME —— 那份选择会被尊重。

# 该 JDK 的 java 主版本号（21 / 17 …）；取不到时输出空串。
jdk_major_version() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1
}

# GraalVM 的 -version 输出里带 GraalVM，据此排除。
jdk_is_graalvm() {
  "$1/bin/java" -version 2>&1 | head -3 | grep -q "GraalVM"
}

# 解析出一个非 GraalVM 的 OpenJDK 21 并 export JAVA_HOME。
# JAVA_HOME 已指向合格 JDK 时保留它，否则按候选顺序回退；都不合格则返回 1。
#
# 注意：下面所有向中文全角标点相邻的变量都必须写成 ${var}。在 LANG=C.UTF-8 下 bash 会把
# 全角标点的字节当成标识符的一部分（isalnum 对高位字节返回真），`$candidate（` 会让整段
# 路径被吞进变量名、展开成空，消息里就只剩标点。
require_gradle_jdk() {
  local candidate major
  for candidate in \
    "${JAVA_HOME:-}" \
    /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
    "$HOME/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" \
    /opt/homebrew/opt/openjdk@21 \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  do
    [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] || continue
    if jdk_is_graalvm "$candidate"; then
      echo "跳过 GraalVM：${candidate}（AGP JdkImageTransform 会失败）" >&2
      continue
    fi
    major="$(jdk_major_version "$candidate")"
    if [ "$major" != "21" ]; then
      echo "跳过 JDK ${major:-未知}：${candidate}（本项目需要 21）" >&2
      continue
    fi
    export JAVA_HOME="$candidate"
    return 0
  done
  echo "找不到可用的 OpenJDK 21。安装 Eclipse Temurin 21：" >&2
  echo "  brew install --cask temurin@21" >&2
  echo "勿使用 GraalVM：AGP JdkImageTransform 会失败。" >&2
  return 1
}
