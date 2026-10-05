# Kujira-Manga 工作约定

## 文档

`docs/` 放专题说明，根目录放 `ARCHITECTURE.md`（架构）、`CHANGELOG.md`（版本）、`README.md`（用户向）。
索引见 [docs/README.md](docs/README.md)。

| 主题 | 文档 |
|---|---|
| 调试 CLI（`./scripts/android`） | [docs/android-cli.md](docs/android-cli.md) |
| 真机插桩测试、HyperOS 限制 | [docs/instrumented-tests.md](docs/instrumented-tests.md) |
| 发布签名与钥匙串 | [docs/release-signing.md](docs/release-signing.md) |
| 分支模型、Release CI、发版步骤 | [docs/release-flow.md](docs/release-flow.md) |

约定：新文档进 `docs/` 并登记索引；已关闭的审计报告与一次性附件修完即删；**文档与代码不符视为缺陷，改文档**。

## 调试

真机/模拟器操作一律走 `./scripts/android`（`doctor` / `devices` / `install-debug` / `logcat` / `exported-logs` / `screenshot` …），不要裸写一长串 `adb`。新调试能力加进这个 CLI，别另起碎片脚本。

## 构建

- JDK 必须是 **Eclipse Temurin 21**（`brew install --cask temurin@21`）；GraalVM 会挂 AGP `JdkImageTransform`。`scripts/jdk-guard.sh` 会在构建前拦截。
- **不要**把本机 JDK 绝对路径写进 `gradle.properties` 的 `org.gradle.java.home`（会打挂 CI）。
- SDK 路径在 `local.properties`。
- 打 Release 前取签名密码：`eval "$(./scripts/android signing-env)"`。

## 验证纪律

默认验证链（改代码后跑）：

```bash
./gradlew :app:testDebugUnitTest
```

- 改了 `app/src/androidTest/**` 或其依赖的生产代码，额外跑 `./gradlew :app:compileDebugAndroidTestKotlin` ——它**不在**默认任务链里，红了不会有任何提示。
- **真机插桩测试默认不做**。只有用户明确要求，或改动只可能在真机行为上体现（文件型 Room、SAF、`PdfDocument`、`WorkerParameters`、Compose/Glass 运行时）时才跑 `./scripts/android test-class <FQCN>`，跑完按 [docs/instrumented-tests.md](docs/instrumented-tests.md) 判定结果。
- 搜索用 `rg`（或 IDE 的 Grep），不要用 macOS 自带 `grep`（BSD grep 不支持 `\|` 交替，会静默匹配不到）。

## 分支与发版

- 日常开发在 **`canary`**，发版也在 `canary`；临时分支合入后删除。
- **日常提交不要碰 `CHANGELOG.md`。** 写不写随你，但**推送前必须确认它是干净的**——
  Release CI 在 `canary` 上、**当且仅当 `CHANGELOG.md` 有改动**的 push 触发，
  随手带一个空章节或半句进去就会白烧一轮 5 分钟以上的 CI 并产出一个空 draft Release。
  积攒的更新内容写在本地，要发版时再合并成章节一起推。
- **只有确定要构建 release 时才动 `CHANGELOG.md`**（与 `version.properties` 同步），然后：
  推送 `canary` → 等 CI（通常 >5 分钟）→ 核对 APK 产物 → 发布 draft 并写更新内容。
  细节见 [docs/release-flow.md](docs/release-flow.md)。
- 架构或安全相关变更后同步核对 `ARCHITECTURE.md`。密钥库、密码、`*.apk` 一律不进 git。
