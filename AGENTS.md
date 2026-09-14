# jmcomic-plus 工作约定

## 文档目录（docs/）

项目说明集中在 **`docs/`**：

| 文件 | 用途 |
|---|---|
| `docs/README.md` | 文档索引 |
| `docs/android-cli.md` | 统一调试 CLI |
| `docs/instrumented-tests.md` | 真机插桩测试 |

根目录：`ARCHITECTURE.md`（架构）、`CHANGELOG.md`（版本）、`README.md`（用户向）。

- 新文档放进 `docs/`，并在 `docs/README.md` 登记。
- **不要**把已关闭的审计报告、一次性复现附件长期留在仓库；修完即删。
- 与代码不符的文档视为缺陷，直接改文档。

## 调试工具（默认）

真机/模拟器调试**一律使用**仓库内 CLI：

```bash
./scripts/android doctor
./scripts/android devices
./scripts/android install-debug
./scripts/android logcat
./scripts/android test-class <FQCN>
```

- 不要再裸写一长串 `adb install` / `adb logcat`；优先 `./scripts/android <子命令>`。
- 细节见 `docs/android-cli.md`；插桩见 `docs/instrumented-tests.md`。
- 新调试能力写进 `scripts/android`，不要另起碎片脚本。

## 构建

- JDK：Homebrew `openjdk@21`（`JAVA_HOME=/opt/homebrew/opt/openjdk@21`）。GraalVM 会挂 AGP `JdkImageTransform`。
- SDK：`local.properties` → `/opt/homebrew/share/android-commandlinetools`。
- 单测：`./gradlew :app:testDebugUnitTest`
- Release 签名密码：环境变量 `JMCOMIC_RELEASE_STORE_PASSWORD` / `JMCOMIC_RELEASE_KEY_PASSWORD`（钥匙串服务名 `jmcomic-plus-release-signing`）。

## 分支

- 日常开发在 `canary`；推送前跑相关单测。
- 安全/架构变更后同步核对 `ARCHITECTURE.md` 与 `CHANGELOG.md`。
