# Kujira-Manga 工作约定

## 文档目录（docs/）

项目说明集中在 **`docs/`**：

| 文件 | 用途 |
|---|---|
| `docs/README.md` | 文档索引 |
| `docs/android-cli.md` | 统一调试 CLI |
| `docs/instrumented-tests.md` | 真机插桩测试 |
| `docs/release-signing.md` | 本地签名与密钥 |
| `docs/release-flow.md` | 分支模型、dev CI 与发版流程 |

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

- JDK：**Eclipse Temurin 21**（`brew install --cask temurin@21`，装在
  `/Library/Java/JavaVirtualMachines/temurin-21.jdk`）。GraalVM 会挂 AGP `JdkImageTransform`。
  构建脚本由 `scripts/jdk-guard.sh` 强制校验（找不到合格 JDK 时直接失败并给出安装命令）。
  **不要**把本机 JDK 绝对路径写进 `gradle.properties` 的 `org.gradle.java.home`（会打挂 CI）。
- SDK：`local.properties` → `/opt/homebrew/share/android-commandlinetools`。
- 单测：`./gradlew :app:testDebugUnitTest`
- Release 签名密码：环境变量 `KUJIRA_MANGA_RELEASE_STORE_PASSWORD` / `KUJIRA_MANGA_RELEASE_KEY_PASSWORD`。
  密码存在 macOS 钥匙串条目 **`Kujira-Manga-Key`**（acct `Utolaris`）里，构建前先取值：
  `eval "$(./scripts/android signing-env)"`。密钥库 `release-key/Kujira-Manga-Key.p12`，别名 `Kujira-Manga-Key`。
  细节见 `docs/release-signing.md`。

## 分支与发版

- **日常开发在 `canary`**；推送前跑相关单测。
- **发版走 `dev`**：发版时将 `canary` 同步到 `dev` 并推送；`dev` 独有 GitHub Actions CI
  （官方/社区 Actions 钉**最新 major**，清单见 `docs/release-flow.md`）构建签名 Release APK 并生成 draft Release。完整流程见
  `docs/release-flow.md`。
- 发版前：更新 `version.properties` 与 `CHANGELOG.md`；安全/架构变更后同步核对 `ARCHITECTURE.md`。
- 临时分支合入 `canary` 后删除；CI/密钥/流程变更先改文档与 workflow。
