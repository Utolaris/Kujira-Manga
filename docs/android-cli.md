# Android 调试 CLI

本仓库的真机/模拟器调试**默认统一走** `./scripts/android`。不要再在会话里裸写一长串 `adb` 命令；新脚本也应调用本 CLI 或复用其设备选择逻辑。

## 依赖

| 组件 | 本机位置（Homebrew） | 说明 |
|---|---|---|
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | `local.properties` 的 `sdk.dir` |
| cmdline-tools | `…/cmdline-tools/latest` | `sdkmanager` / `avdmanager` / `apkanalyzer` |
| platform-tools | `…/platform-tools` | `adb` |
| build-tools | `…/build-tools/37.0.0` | `aapt` / `apksigner` |
| JDK | `/opt/homebrew/opt/openjdk@21` | 跑 Gradle；**不要**用 GraalVM 当 `JAVA_HOME` |

CLI 会自动探测 SDK（`ANDROID_HOME` → `ANDROID_SDK_ROOT` → 常见路径），并把上述 bin 加入 PATH。

健康检查：

```bash
./scripts/android doctor
```

## 常用命令

```bash
# 设备
./scripts/android devices
./scripts/android doctor

# 安装 / 启动
./scripts/android install-debug          # 编译 + 装到唯一真机
./scripts/android install-debug <serial>
./scripts/android launch
./scripts/android stop
./scripts/android clear                  # 清应用数据

# 调试
./scripts/android logcat
./scripts/android logcat -c              # 先清空再跟
./scripts/android screenshot             # → build/screenshot.png
./scripts/android screenrecord 15        # → build/screen.mp4
./scripts/android dumpsys                # 当前 Activity/窗口
./scripts/android bugreport              # → build/bugreport.zip

# 分析
./scripts/android apk-info app/build/outputs/apk/debug/*.apk
./scripts/android apk-sign  app/build/outputs/apk/release/*.apk

# 发布签名（从钥匙串取密码，详见 docs/release-signing.md）
eval "$(./scripts/android signing-env)"   # 导出 KUJIRA_MANGA_RELEASE_*_PASSWORD
./gradlew :app:assembleRelease

# 插桩测试（转调 run-instrumented-tests.sh）
./scripts/android test
./scripts/android test-class com.par9uet.jm.cache.atom.CacheFilesDeviceTest

# HyperOS：允许 instrumentation 后台弹窗
./scripts/android appops-allow
```

## 约定

1. **真机优先**：不带序列号时自动选唯一真机，忽略模拟器；多台真机会报错并列出。
2. **安装默认覆盖安装**（`install -r -t`），保留登录/设置；只有插桩脚本的 `--fresh` 才卸载。
3. **结果以输出流为准**，不要只看 adb 退出码（见 `docs/instrumented-tests.md`）。
4. **JDK**：CLI 在缺省时会把 `JAVA_HOME` 指到 Homebrew OpenJDK 21。
5. 新调试能力请加进 `scripts/android`，并在本文件补一行用法，而不是另起碎片脚本。

## 与既有脚本的关系

| 脚本 | 关系 |
|---|---|
| `scripts/android` | **默认入口** |
| `scripts/install-debug.sh` | 仍可用；逻辑与 `install-debug` 一致 |
| `scripts/run-instrumented-tests.sh` | `./scripts/android test` 转调它；细粒度参数直接用原脚本 |

## 相关文档

- 真机插桩测试与 HyperOS 限制：[instrumented-tests.md](./instrumented-tests.md)
- 架构与构建：[../ARCHITECTURE.md](../ARCHITECTURE.md)
