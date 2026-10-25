# docs/ — 项目文档

只保留**当前有效**的说明。已交付 feature 的施工报告、已关闭的审计与一次性附件
不在这里堆——问题修完后从仓库删掉，语义提炼进 [../ARCHITECTURE.md](../ARCHITECTURE.md)。

| 文档 | 内容 |
|---|---|
| [android-cli.md](./android-cli.md) | 统一 Android 调试 CLI（`./scripts/android`）用法与约定 |
| [instrumented-tests.md](./instrumented-tests.md) | 真机插桩测试、HyperOS 限制、结果判定 |
| [color-palettes.md](./color-palettes.md) | 五套预设的来源、角色映射与文字对比度约定 |
| [ui-motion.md](./ui-motion.md) | 封面展开/拖动退出、V3 按压、触边震动与收藏心形反馈 |
| [notifications.md](./notifications.md) | 漫画合集订阅、账号通知箱、图文渲染与已读协议 |
| [dinner.md](./dinner.md) | 绅夜食堂文章协议、原生图文、站内跳转与推广过滤 |
| [release-signing.md](./release-signing.md) | 发布签名密钥、钥匙串条目与打 Release 包的流程 |
| [release-flow.md](./release-flow.md) | 分支模型（canary）、Release CI（Actions 最新 major）与发版步骤 |

根目录另有：

- [../ARCHITECTURE.md](../ARCHITECTURE.md) — 四层架构、边界断言与安全契约（架构变更时同步）
- [../CHANGELOG.md](../CHANGELOG.md) — 用户可见版本说明
- [../AGENTS.md](../AGENTS.md) — 代理/开发工作约定
