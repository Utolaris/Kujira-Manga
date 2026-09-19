# Kujira-Manga

[JM](https://jmcomic.plus) 第三方 Android 客户端。基于 [HongShi2333/jmcomic-next](https://github.com/HongShi2333/jmcomic-next) 持续维护，数据解析依赖 [JUKOMU/JMComic-Api-Java](https://github.com/JUKOMU/JMComic-Api-Java)。

> 本仓库原名 `jmcomic-plus`，自 v1.4.4 起更名为 **Kujira-Manga**：仓库名、应用显示名和安装包名
> 都已更换。应用标识由 `jmcomic.plus` 变为 `kujira.manga`，签名密钥也已重建，
> 系统会将其视为**全新应用**，旧版本的设置、收藏与下载数据都不会自动迁移。

- 系统要求：Android 11（API 30）及以上
- 当前版本：`1.4.8`（versionCode `148`）
- 安装包名：release `kujira.manga`，debug `kujira.manga.debug`（与旧包名签名不同，系统会视为新应用，数据不会自动迁移）
- 调试入口：`./scripts/android`（设备、安装、logcat、插桩测试等，见 [docs/android-cli.md](docs/android-cli.md)）
- 发布签名：密钥库 `release-key/Kujira-Manga-Key.p12`，密码存于钥匙串条目 `Kujira-Manga-Key`（见 [docs/release-signing.md](docs/release-signing.md)）
- 分支与发版：日常开发 `canary`，发版同步到 `dev` 由 CI 出包（见 [docs/release-flow.md](docs/release-flow.md)）

---

## 功能特色

### 界面与导航

统一玻璃质感体系：顶栏、底部导航、菜单、弹窗、提示与页面切换动效一致。首页分类可直达常用推荐位；收藏与搜索结果会记住浏览位置；详情页点标签/作者进搜索后可原路返回详情。

### 阅读器

- 双指缩放与拖动：双指不再误触翻页；放大后锁定当前页，中央双击还原
- 本地章节支持当前目录、历史目录与 ZIP 三种布局
- 进程被系统回收后重进应用，可回到上次阅读章节

### 图片与网络

- 阅读图链路带优先级、去重、预加载、解码与内存/磁盘缓存
- 多 CDN 竞速与节点健康度；全节点变慢时停止无效竞速
- 内置 API 为主数据源，可配置 DoH；登录会话可自动恢复并重试

### 收藏

本地优先（Room + 分页）：文件夹、搜索筛选、后台同步与手动刷新；远端无变化时不刷新列表，避免封面闪烁。

### 下载与缓存

- 按漫画/章节下载，可暂停、恢复、批量重下
- 自定义缓存目录（系统文件选择器），切换时自动迁移；迁移成功才切换，失败保留原状态
- 支持导出 PDF（分章 / 合并）

### 隐私与入口

- 应用锁（密码 / 图案）
- 启动器图标伪装（相册 / 系统工具等别名）
- 支持设置与下载缓存的备份 / 恢复

---

## 开发

### 文档

- [四层架构约束](ARCHITECTURE.md)
- [Android 调试 CLI](docs/android-cli.md)（默认调试入口）
- [真机插桩测试](docs/instrumented-tests.md)
- [分支与发版 / dev CI](docs/release-flow.md)

### 环境

- 语言级别 Java 21（构建请用 Eclipse Temurin 21：`brew install --cask temurin@21`；不要用 GraalVM 当 Gradle daemon）
- Gradle Wrapper 9.7.1 / AGP 9.4.0 / Kotlin 2.4.20

### 装到手机

```bash
./scripts/android doctor           # 检查 SDK / 设备
./scripts/android install-debug    # 编译并安装到唯一真机
./scripts/android logcat
```

仍兼容 `./scripts/install-debug.sh`；细粒度插桩测试用 `./scripts/android test` 或原脚本。

### 真机插桩测试

```bash
./scripts/android test             # 等价 run-instrumented-tests.sh
./scripts/run-instrumented-tests.sh                      # 全量
./scripts/run-instrumented-tests.sh -p com.par9uet.jm.ui # 一个包
./scripts/run-instrumented-tests.sh -c <类名> -m <方法名>
```

**HyperOS / MIUI**：需允许「后台弹出界面」（`appops 10021`），否则测试 Activity 会被压回桌面。脚本只做 preflight，不会自动改 appops；详见插桩文档。

---

## 致谢

- [HongShi2333/jmcomic-next](https://github.com/HongShi2333/jmcomic-next) — 上游客户端
- [JUKOMU/JMComic-Api-Java](https://github.com/JUKOMU/JMComic-Api-Java) — 接口与解析
