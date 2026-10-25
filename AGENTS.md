# Kujira-Manga 工作约定

## 开发与调试

- 开发、发版都用 `canary`；临时分支合入后删除。
- 搜索使用 `rg`。
- 真机操作统一使用 `./scripts/android`；新能力加进该 CLI。见 [调试说明](docs/android-cli.md)。

## 构建与验证

- 使用 **Eclipse Temurin 21**；JDK 路径由本机环境配置，SDK 路径放 `local.properties`。
- 改代码后运行 `./gradlew :app:testDebugUnitTest`。
- 修改 `app/src/androidTest/**` 或其依赖的生产代码后，额外运行 `./gradlew :app:compileDebugAndroidTestKotlin`。
- **只有用户明确要求，才能进行真机测试。** 未验证的部分如实说明。
- 用户要求真机测试时，用 `./scripts/android test-class <FQCN>`，按 [真机测试说明](docs/instrumented-tests.md) 判定结果。

## 文档

- 专题文档放 `docs/`，新增时登记 [索引](docs/README.md)；根目录保留 `ARCHITECTURE.md`、`CHANGELOG.md`、`README.md`。
- 文档必须与代码一致；架构或安全变更后核对 `ARCHITECTURE.md`。
- 已关闭的审计报告、一次性附件及时删除。

## 发布

- **日常不改 `CHANGELOG.md`，推送前确认它干净。** `canary` 上包含该文件改动的 push 会触发 Release CI；待发布内容先在本地积攒。
- 确定发版时，再一起更新 `CHANGELOG.md` 和 `version.properties`：推送 `canary` → 等 CI → 核对 APK → 发布 draft 并填写更新内容。见 [发版流程](docs/release-flow.md)。
- Release 构建前运行 `eval "$(./scripts/android signing-env)"` 获取签名环境。见 [签名说明](docs/release-signing.md)。
- 密钥库、密码、`*.apk` 不进 Git。
