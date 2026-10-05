# 分支与发版

## 分支模型

| 分支 | 角色 |
|---|---|
| `canary` | **日常开发**默认分支（GitHub default）。功能合入、单测通过后进这里；发版也在此分支完成。 |
| `main` | 历史稳定线，当前不再作为开发入口（保持兼容，不主动改写）。 |
| 临时分支 | `feat/*`、`fix/*`、`chore/*` 等；合入 `canary` 后删除，不要长期留远端。 |

```text
canary  ──(改动 CHANGELOG.md 并 push)──►  GitHub Actions  ──►  draft Release + APK
   ▲                                         │
   └── 日常开发 / PR 合入                    └── 人工确认产物后发布并写更新内容
```

## 发版流程（标准）

1. **在 `canary` 上完成变更**，跑相关单测：
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
2. **写版本材料**（AI/维护者）——**这一步只在真的要构建 release 时做**：
   - `version.properties`：`VERSION_NAME` / `VERSION_CODE`
   - `CHANGELOG.md`：在顶部新增该版本章节（用户可见说明）
   - 同步 README 中的「当前版本」等过期字段

   > 日常开发**不要提前改** `CHANGELOG.md`。CI 靠它的 diff 触发，误触会白烧一轮构建
   > 并产出空 draft Release。积攒的更新内容先放本地，要发版时再合并成章节。
3. **推送全部代码到远端**：
   ```bash
   git push origin canary
   ```
   `CHANGELOG.md` 有改动时 CI 会自动构建（通常超过 5 分钟）。
4. **确认产物**：Actions 上传 APK Artifact，并创建/更新对应 tag 的 **draft** GitHub Release（正文为空）。
5. **发布**：核对 draft Release 附件与 `CHANGELOG.md` 对应章节后，写好更新内容并发布：
   ```bash
   gh release edit "vX.Y.Z" --body-file <更新内容.md> --draft=false
   ```
   无 CI 签名时的兜底（本地签名）仍见 [release-signing.md](./release-signing.md)。

## Release CI

- 路径：`.github/workflows/release.yml`
- **当且仅当** `CHANGELOG.md` 被改动的 `push` 到 `canary` 时运行（或手动 `workflow_dispatch`）。
  改代码但不动 `CHANGELOG.md` **不会**触发；需要纯代码变更也验证工具链时，用 `workflow_dispatch` 手动跑。
- CI **不自动生成更新内容文本**：draft Release 正文留空，由维护者在确认产物后手写。
- Action 版本（2026-09-24 对齐最新 major；改 workflow 前先更新本表）：

  | Action | 版本 | 用途 |
  |---|---|---|
  | `actions/checkout` | **v7** | 源码 |
  | `actions/setup-java` | **v6** | Temurin 21 |
  | `gradle/actions/setup-gradle` | **v6** | Gradle 缓存/配置 |
  | `android-actions/setup-android` | **v4** | Android SDK |
  | `actions/upload-artifact` | **v7** | APK 产物 |
  | `softprops/action-gh-release` | **v3** | draft Release |
- 「放回仓库」指：把 CI 构建出的 APK 挂到 **GitHub Release**（tag = `v${VERSION_NAME}`），不把 `*.apk` 提交进 git（见 `.gitignore`）。

### 所需 GitHub Secrets

| Secret | 说明 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `release-key/Kujira-Manga-Key.p12` 的 base64（单行） |
| `KUJIRA_MANGA_RELEASE_STORE_PASSWORD` | 钥匙库 storePassword |
| `KUJIRA_MANGA_RELEASE_KEY_PASSWORD` | keyPassword（与 store 相同） |

本地一次性上传示例（密码请从钥匙串取出，勿写入仓库）：

```bash
gh secret set RELEASE_KEYSTORE_BASE64 --repo Utolaris/Kujira-Manga \
  --body "$(base64 < release-key/Kujira-Manga-Key.p12 | tr -d '\n')"
eval "$(./scripts/android signing-env)"
gh secret set KUJIRA_MANGA_RELEASE_STORE_PASSWORD --repo Utolaris/Kujira-Manga --body "$KUJIRA_MANGA_RELEASE_STORE_PASSWORD"
gh secret set KUJIRA_MANGA_RELEASE_KEY_PASSWORD  --repo Utolaris/Kujira-Manga --body "$KUJIRA_MANGA_RELEASE_KEY_PASSWORD"
```

## 约定

- 发版提交信息可用 `release: vX.Y.Z …`；日常开发仍遵守 AGENTS.md。
- CI/密钥/流程变更：先改本文档与 workflow，再改实践。
- 不要把密钥库、密码、`*.apk` 提交进 git。
