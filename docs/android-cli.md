# Android 调试 CLI

本仓库的真机/模拟器调试**默认统一走** `./scripts/android`。不要再在会话里裸写一长串 `adb` 命令；新脚本也应调用本 CLI 或复用其设备选择逻辑。

## 依赖

| 组件 | 本机位置（Homebrew） | 说明 |
|---|---|---|
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | `local.properties` 的 `sdk.dir` |
| cmdline-tools | `…/cmdline-tools/latest` | `sdkmanager` / `avdmanager` / `apkanalyzer` |
| platform-tools | `…/platform-tools` | `adb` |
| build-tools | `…/build-tools/37.0.0` | `aapt` / `apksigner` |
| JDK | `/Library/Java/JavaVirtualMachines/temurin-21.jdk` | 跑 Gradle（Eclipse Temurin 21）；**不要**用 GraalVM 当 `JAVA_HOME` |

CLI 会自动探测 SDK（`ANDROID_HOME` → `ANDROID_SDK_ROOT` → 常见路径），并把上述 bin 加入 PATH。

Gradle 构建**不再**向 JVM 传空的 `-Dhttps.proxyPort=` 等参数（那只会打出
`Invalid https.proxyPort ''` 警告，并不能可靠清代理）。需要代理时配置
`~/.gradle/gradle.properties` 或环境变量；仓库内 `gradle.properties` 不配代理。

健康检查：

```bash
./scripts/android doctor
```

## 常用命令

```bash
# 设备
./scripts/android devices
./scripts/android doctor
# 无线调试：配对端口/配对码来自「使用配对码配对设备」弹窗；
# 连接要用无线调试主页上的「IP 地址和端口」（通常不是配对端口）。
# 已配对设备也可走 mDNS 自动发现（adb-reconnect）。
./scripts/android adb-pair 192.168.5.103:38615 795546
./scripts/android adb-connect 192.168.5.103:<连接端口>
./scripts/android adb-reconnect
./scripts/android adb-reconnect 192.168.5.103:<连接端口>
# Clash TUN 与 adb：见下方「Clash TUN」小节。

# 安装 / 启动
./scripts/android install-debug          # 编译 + 装到唯一真机
./scripts/android install-debug <serial>
./scripts/android launch
./scripts/android stop
./scripts/android clear                  # 清应用数据

# 调试
./scripts/android logcat
./scripts/android exported-logs         # 读取 debug 应用内导出的最近日志及安装信息 → build/device-logs/
./scripts/android logcat -c              # 先清空再跟
./scripts/android screenshot             # → build/screenshot.png
./scripts/android screenrecord 15        # → build/screen.mp4
./scripts/android dumpsys                # 当前 Activity/窗口
./scripts/android input <serial> tap <x> <y>  # 点击；也支持 swipe 和 keyevent 数字参数
./scripts/android ui-dump <serial>       # 当前界面节点 → build/ui.xml
./scripts/android bugreport              # → build/bugreport.zip

# 分析
./scripts/android apk-info app/build/outputs/apk/debug/*.apk
./scripts/android apk-sign  app/build/outputs/apk/release/*.apk
# 登录链路日志（应用内 Log 导出 / adb logcat 过滤 tag Login）
./scripts/android logcat | rg '\[KUJIRA-MANGA\] Login|LoginSessionGate|verifyCandidate|login businessCode'

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
4. **JDK**：CLI 编译前会过 `scripts/jdk-guard.sh`，把 `JAVA_HOME` 锁到 Eclipse Temurin 21
   （`brew install --cask temurin@21`）；解析不到合格 JDK 会直接失败并打印安装命令。
5. 新调试能力请加进 `scripts/android`，并在本文件补一行用法，而不是另起碎片脚本。

## Clash TUN 与无线 adb

Clash Verge / mihomo 开 TUN 且 `auto-route: true` 时，默认可能把局域网与组播也收进虚拟网卡，
导致 `adb pair` / `adb connect` / mDNS 出现 `No route to host`。

**规则里 `IP-CIDR,192.168.0.0/16,DIRECT` 不够**——DIRECT 是策略，不改变 TUN 是否抢路由。
需要在配置里把局域网从 auto-route 排除（mihomo `inet4-route-exclude-address`）：

```yaml
# Clash Verge → 订阅 Merge.yaml（profiles/Merge.yaml）与扩展 Script.js 均已写入
tun:
  auto-route: true
  auto-detect-interface: true
  strict-route: false
  inet4-route-exclude-address:
    - 127.0.0.0/8
    - 10.0.0.0/8
    - 172.16.0.0/12
    - 192.168.0.0/16
    - 169.254.0.0/16
    - 224.0.0.0/4   # mDNS 组播，无线调试发现依赖它
```

注意：

- **不要**排除 `198.18.0.0/15`（fake-ip 段），否则 TUN 下域名分流会坏。
- 改 Merge/Script 后需在 Clash Verge 里 **重新应用订阅/重载配置** 再开 TUN。
- 已配对设备在 TUN 下也可：`./scripts/android adb-reconnect`（优先 mDNS）。
- 手机侧 Clash 一般不影响 adb；电脑侧 TUN + 未排除局域网才是主因。

## 与既有脚本的关系

| 脚本 | 关系 |
|---|---|
| `scripts/android` | **默认入口** |
| `scripts/install-debug.sh` | 兼容入口，转调 `install-debug`（不再有第二份实现） |
| `scripts/run-instrumented-tests.sh` | `./scripts/android test` 转调它；细粒度参数直接用原脚本 |

## 相关文档

- 真机插桩测试与 HyperOS 限制：[instrumented-tests.md](./instrumented-tests.md)
- 架构与构建：[../ARCHITECTURE.md](../ARCHITECTURE.md)
