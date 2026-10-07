# 四层架构约束

项目增量采用 L1～L4 四层架构。目标是让功能入口、流程决策和底层实现容易定位，
而不是为了分层机械增加文件数量。尚未迁移的代码应被明确列为例外，不能只改目录名称。

> 本文描述**当前代码的真实状态**。与代码不符的措辞视为文档缺陷，应直接修正。
> 统计口径：`app/src/main/java/com/par9uet/jm` 下 `*.kt`，`find | wc -l` 汇总。
> 最近核对 **2026-10-05**（canary）：**387** 文件 / **49,741** 行。
> 耦合表用 `python3 scripts/check-coupling.py` 复现（细分口径，见脚本头）。

## 层级

| 层 | 职责 | 主要落点 |
| --- | --- | --- |
| L1 Entry | 只接收事件并交给 L2，不做业务判断 | `ui/screens`（`*Screen.kt`）、`ui/navigation`（含 `LocalMainNavController`）、`ui/components`（只收参数、只读 `ui/models` 环境值）、`ui/haptics`、`ui/interaction`、`MainActivity`、`App`（UI 组合根：提供环境值）、`worker/DownloadComicWorker`、`worker/CacheMigrationWorker`；平板布局 CompositionLocal 定义在 `ui/models/TabletLayout.kt`，由 `App` 调用的 `ui/screens/TabletLayout.kt` 的 `ProvideTabletLayout` 注入 |
| L2 Coordinator | 集中保存流程顺序、分支和跨边界协调 | `ui/viewModel`（**18** 个）、`reader/ReaderImagePipeline`、`reader/coordinator`、`download/coordinator`（含 `DownloadManager`）、`cache/migration` 的协调器与通知适配、`favorites/sync`、`favorites/presentation/FavoritesViewModel`、`session`（`UserManager`、`UserRepository`、`AuthenticatedRequestRecovery`、`SessionReadinessHolder`）、`startup/PostStartupCoordinator` |
| L3 Molecule | 组合多个原子能力，完成一个完整业务动作 | `reader/molecule`、`download/molecule`（含 `DownloadLibraryQueries`）、`cache/migration` 的操作端口与实现、`favorites/usecase`、`backup/BackupRestoreOperations`、`download/export/DownloadExportOperations`、`repository/impl` |
| L4 Atom | 每个原子只负责一个底层契约 | `database`、`storage`、`retrofit`、`data`、`network`（含内置 API 客户端三件套）、`image`、`coil`、`cache/atom`、`cache/CacheBudget`、`reader/atom`、`download/atom`、`download/export/PdfExport`、`favorites/data`（含 `FavoriteStore`）、`update`（含 `AppUpdateDownloadManager`）、`contentfilter`、`launcher`、`utils` |
| Shared Contract | 不含行为的稳定 DTO，可被各层依赖 | `core/model`（`CommonUIState`、`User`、`RemoteSetting`、`SignInData`）、`core/network`（`NetWorkResult` / `ResponseWrapper` / `AuthFailure` / `AuthenticatedSessionRequiredException` / `AuthAttemptOrigin` / `FormBodyNullParameter`）、`data/models`（`Comic` / `Comment` / `WeekData` / `ComicPage` / `CommentPage` / `ComicSearchPage` / `ComicPageList` / `ActionResult` / `HomeComicSwiperItem`；零出度）、`favorites/model/FavoritesModels`、`reader/ReaderImageModels`、`download/model/DownloadLibraryModels` |

依赖方向 `L1 -> L2 -> L3 -> L4`。L3 之间、L4 之间不得为方便横向调用；
需要组合提升到 L3，需要决定顺序提升到 L2。`di` 是组合根，可引用所有层但不得承载业务判断。
跨域适配器（如 `di/UserManagerFavoriteSession`）归 `di`，领域包只依赖 model 端口。

## 目录约定

**不存在** `feature/<name>/` 这一层。领域包直接位于 `com.par9uet.jm` 之下，
只有完成迁移的领域才带分层子目录，且各领域用词并不统一：

```text
reader/                      L2 ReaderImagePipeline + coordinator/ molecule/ atom/
download/                    L2 coordinator/、L3 molecule/、L4 atom/、export/（L3 操作 + L4 PDF）
favorites/                   model/ 共享契约、presentation/ L2、sync/ L2、usecase/ L3、data/ L4
cache/                       atom/ L4、CacheBudget L4、migration/ L2+L3，另有未归位的 L4
ui/                          screens/ 之外：components、glass、navigation、theme、models、
                             haptics、interaction、pagingSource、viewModel
session/ network/ storage/   L2 会话协调 / L4 网络设施 / L4 偏好与持久化
update/ backup/ contentfilter/ launcher/ startup/   扁平包，按类判断层级
data/ repository/ retrofit/   历史命名保留
```

新代码沿用所在领域已有的子目录命名；跨领域新建时用 `coordinator / molecule / atom`。
小功能不必建子目录，但仍守同样的依赖方向。
**`store` 包已整体删除（2026-09-12），新代码不得重建**；`task/` 空目录同样已删。
测试源码里仍留着 `app/src/test/java/com/par9uet/jm/store/` 旧包路径（会话、收藏排序、启动器测试），
属包名残留，不影响分层，归档时按被测类型归位。

## 边界测试断言

`ArchitectureBoundaryTest` 固定以下边界，防止补丁重新引入反向依赖。
断言同时扫描 **import 与全限定引用**（`forbiddenQualifiedUsages`，非注释行），
防止 `com.par9uet.jm.network.DohManager` 这类写法绕过统计。**改依赖前先看这张表。**

| 受约束位置 | 禁止 import / 引用 |
| --- | --- |
| `ui`（整体） | `database.`、`retrofit.model.`、`favorites.data.` |
| `App.kt` | `retrofit.model.` |
| `core` | `ui.` |
| `ui/components` | `storage.`、`repository.`、`database.`、`session.`、`cache.`、`download.`、`backup.`、`update.`、`network.`、`reader.`、`favorites.`、`ui.viewModel.` |
| `ui/components`、`ui/glass` | `ui.screens.` |
| `ui/screens`（整体） | `com.par9uet.jm.cache.`；**`org.koin.compose.getKoin` / `org.koin.androidx.compose.getKoin`**——依赖只能来自 Feature ViewModel 或 App 组合根参数 |
| `ui/viewModel/ComicReadViewModel.kt` | `java.io.`、`java.util.zip.`、`database.`、`cache.` |
| `ui/screens/CacheCleanupScreen.kt`、`DownloadComicDetailScreen.kt` | `java.io.`、`kotlinx.coroutines.`、`download.coordinator.DownloadManager`、`reader.ReaderImagePipeline`、`database.`、`download.export.*`、`cache.atom.` |
| `ui/screens/ComicDetailScreen.kt`、`readScreen/ComicReadScreen.kt` | `download.coordinator.DownloadManager` |
| `ui/screens/ExtractCodeScreen.kt` | `repository.` |
| `DohSettingScreen` / `AppLockSettingScreen` / `WelcomeScreen` / `CheckUpdateScreen` / `CheckUpdateInfoCards` / `BackupRestoreScreen` | `network.`、`storage.`、`session.UserManager`、`getKoin`；CheckUpdate/Backup 另禁 okhttp/gson/FileProvider/database/BackupManager/DownloadManager/AppUpdateDownloadManager |
| `cache/atom` | `ui.`、`store.`、`reader.` |
| `data`（整体） | `repository.`、`session.`、`reader.` |
| `retrofit`（整体） | `data.`、`store.`、`session.`、`network.`（**例外**：`network.applyAppHttpDefaults` / `applyHttpLogging` 等 OkHttp 工厂） |
| `network`（整体） | `repository.`、`data.`、`session.`、`retrofit.` |
| `session`（整体） | `ui.`、`data.`（`data.models.` 除外）、`repository.` |
| `favorites`（整体） | `ui.` |
| `favorites/data` | `download.coordinator.`、`repository.`、`session.`（会话适配器在 `di/`） |
| `favorites/model`、`favorites/presentation` | `database.` |
| `backup` | `ui.`、`download.coordinator.` |
| `update`（整体） | `ui.` |
| `download/export` | `download.molecule.` |
| `reader/atom` | `reader.molecule.`、`reader.coordinator.`、`ui.`、`worker.`、`store.` |
| `reader/molecule` | `reader.coordinator.`、`ui.`、`worker.`、`store.` |
| `worker/DownloadComicWorker.kt` | `database.`、`repository.`、`reader.`、`store.`、`download.molecule.`、`download.atom.`、`coil.`、`java.io.` |
| `worker/CacheMigrationWorker.kt` | `database.`、`repository.`、`reader.`、`store.`、`download.`、`cache.`（`cache.migration.` 除外）、`coil.`、`java.io.`、`android.provider.`、`MainActivity`、`R` |
| `ui/viewModel/CachePathViewModel.kt` | `worker.`、`androidx.work.` |
| `cache/migration` | `ui.`、`worker.`、`store.`、`reader.`、`download.` |
| `download/molecule` | `store.`、`ui.`、`worker.`、`download.coordinator.`、`reader.`、`java.io.`、`androidx.work.` |
| `download/atom` | `download.molecule.`、`store`、`download.coordinator.`、`reader.`、`ui.`、`worker.`、`database.dao.`、`database.AppDatabase` |
| `download/coordinator/DownloadManager.kt` | `download.coordinator.DownloadComicCoordinator`、`database.`、`download.atom.`、`java.io.` |
| `store`（已删除，断言保留防复活） | `ui.`、`worker.` |
| `utils` | `cache.`、`data.` |

## 耦合现状

`python3 scripts/check-coupling.py`（**2026-10-05 20:55 / canary，含本次改动**）。
口径：模块 = 一二级包目录，**细分模块单列**（`core/model` 与 `core` 根目录分开、
`reader/atom` 与 `reader` 根包分开）；Ce = import 到的模块数，Ca = 依赖它的模块数，
I = Ce/(Ce+Ca)。下表只保留决策相关行，完整列表以脚本输出为准。

⚠️ **脚本口径 ≠ 递归总数**：`core` 行只算根目录 3 文件/122 行（`core/model`、
`core/network` 另列）；`reader` 行只算根包 16 文件/2387 行。要总数得自己 `find -name '*.kt'`。

| 模块 | 文件 | 行数 | Ce | Ca | I | 判断 |
| --- | --- | --- | --- | --- | --- | --- |
| `di` | 9 | 785 | 32 | 1 | 0.97 | 组合根，合法 |
| `ui/screens` | 48 | 16,825 | 22 | 1 | 0.96 | 最大一块；直连已清零，见「表现层直连」 |
| `ui/viewModel` | 18 | 3,893 | 27 | 3 | 0.90 | L2；扇出最高但多为契约与偏好 |
| `ui/components` | 22 | 2,224 | 10 | 2 | 0.83 | 已收窄，无 L3/L4 领域依赖 |
| `favorites/presentation` | 2 | 559 | 7 | 2 | 0.78 | L2 |
| `favorites/data` | 14 | 1,251 | 10 | 3 | 0.77 | Room + 窄端口 |
| `reader`（**仅根包**） | 16 | 2,387 | 8 | 6 | 0.57 | 根包说明见「阅读器链路」 |
| `network` | 17 | 2,008 | 5 | 11 | 0.31 | DoH / RemoteConfig / 内置客户端 |
| `storage` | 17 | 2,304 | 5 | 15 | 0.25 | 偏好与持久化收口 |
| `core`（**仅根目录**） | 3 | 122 | 2 | 15 | 0.12 | `BaseRepository`、`ToastManager` |
| `core/model` | 5 | 101 | 0 | 9 | 0.00 | 共享 DTO，**真正的零出度** |
| `utils` | 14 | 848 | 1 | 28 | 0.03 | 稳定，不要动 |
| `data`（= `data/models`） | 16 | 422 | 0 | 24 | 0.00 | 零出度契约，不要动（`data/comic` 另列） |
| 根包（`App` / `MainActivity` / `JmApplication`） | 3 | 739 | 1–12 | 0 | 1.00 | 入口 |

`ui/screens` 内最大文件：`ComicDetailScreen` 915、`LocalSettingScreen` 851、
`BackupRestoreScreen` 781、`ComicReadScreen` 736、`FavoritesModalHost` 734、
`CheckUpdateScreen` 694。**这些是可读性问题，不是跨层耦合**——不再按行数机械拆分。
真要动手优先 `DownloadComicDetailScreen`（跨 4 层），而不是 `LocalSettingScreen`（纯设置项渲染）。

### 不建议动

- **`data` / `utils` / `database/model` / `retrofit/model` / `core`**：Ca 高但 Ce 极低，
  是被依赖的稳定契约，拆分只会制造转发层。
- **`reader` 根包文件**：粒度已足够小且可独立测试，下沉只增加目录层级。
- **`di`**：I=1.00 是组合根的应有形态。
- **`ui/viewModel` 读 `storage` 偏好**、读 `cache.atom` 1 处（`CacheCleanupViewModel`）
  与 `cache/migration` 2 处：属 L2 选定适配器与既有偏好惯例，**不算待修范围**。

### 表现层直连：已清

`python3 scripts/check-coupling.py <模块>` 可复现。「违规」指跨过 L2 直读 L4 设施，
或反向依赖上层；`data.models` 是共享契约，不算违规。

- **`ui/components`**：出边只有 `ui/*` 同层设施、`coil`、`image`、`utils`、`BuildConfig`
  与 `data.models`，**无 L3/L4 领域依赖**。
- **`ui/viewModel`**：`retrofit/model` 已清零，无 `favorites/data`、无 `database`。
- **`ui/screens`**：`download/coordinator` 2、`repository` 1 已消除；`storage` / `network` / `cache`
  **已清零**（早期口径曾是 23 / 7 / 1，随 `getKoin` 收口下沉 `ui/viewModel`）。
  仍直连 `contentfilter`（搜索语法/标签排除，契约向）、`backup`、`session`
  （全部为 `SessionReadiness` 枚举，**无 `UserManager`**）、`favorites/presentation`、
  `download/model`、`update`、`reader` 契约类型。

**下一阶段收口目标**：Screen 里散落的 `session.UserManager` 鉴权分支，
改为经 ViewModel 暴露状态，而非 Screen 直读。`favorites/presentation.FavoritesViewModel`
是 L2，Screen 直接持有属合法 L1→L2。

## 已收工的迁移（结论，勿重复劳动）

以下工作已完成且由边界测试钉住，**不需要再排期**：

- **`store` 包清空删除**（2026-09-12）：`UserManager`/`UserRepository`/
  `AuthenticatedRequestRecovery`/`SessionReadinessHolder` → `session`；
  `FavoriteStore` 及其 SQL 侧 → `favorites/data`；`RemoteConfigManager` → `network`；
  `LocalSettingManager` 及各种偏好 → `storage`；`BackupManager` → `backup`；
  `ToastManager` → `core`；共享 DTO → `core/model`。
- **5 个历史依赖环消除**：`data↔repository`、`data↔retrofit`、`retrofit↔store`、
  `repository↔store`、`data↔reader`，以及评审发现的 `network↔session↔retrofit` 三边环
  （修法：`AuthenticatedSessionRequiredException` 下沉 `core/network`，
  `network` 定义 `AuthenticatedRequestGate` 端口由组合根绑到 `session/AuthenticatedSessionGate`，
  `Retrofit.kt` 参数改 `okhttp3.Dns` 由组合根注入 `DohManager`）。
  随之消除 `session→data`、`network→repository`、`network→data`。
  legacy L4（`storage`/`data`/`network`）仍有待处理 SCC，后续迁移**先抽窄端口再切断反向 import**。
- **`ComicRepository` domain-only**：`toXxx()` mapper 下沉 `repository/impl`，
  wire DTO 不再渗到表现层。分页元数据用 `data/models` 的 `ComicPage`/`CommentPage`/`ComicSearchPage`
  表达（`ComicPage.total` 可空，watch_list 不返回总数）。
  **剩余 legacy boundary**：`UserRepository` 仍经 `CandidateSession` 暴露
  `LoginResponse`/`okhttp3.Cookie`；`RemoteSettingRepository` 仍返回 `RemoteSettingResponse`。
- **下载 4 个 UI 文件直连 Room**：`ui` 整体禁 `database`，DAO 观察收敛到
  `download/molecule/DownloadLibraryQueries`，UI 用 `download/model` 契约。
- **`ui/components` 收窄**：`LocalMainNavController` 归位 `ui/navigation`；
  远端图片主机/详情预置/详情取数改 `ui/models` 环境值 + 组合根提供；
  `ComicPicImage` 移入 `ui/screens/readScreen`；`ComicLazyGrid` 屏蔽标签改为入参。

## 有意保留的例外

以下几处跨层是**刻意设计**，不要"顺手修正"：

1. 下载协调器直接用 DAO 与反馈适配器——进度与同组任务状态决定通知和终态分支，拆散反而更难读。
2. 更新协调器直接用下载/安装端口，备份协调器直接用校验/提取能力——结果直接决定流程分支，
   不为它们加只做转发的用例层。
3. 缓存清理协调器经组合根窄回调（`CacheMigrationDownloadGate`）协调下载与阅读器资源生命周期；
   导出协调器直接调 PDF 文件端口。L2 的跨功能协调，L4 不得反向调用。
4. `RemoteConfigManager` 经 `network` 包内窄端口 `RemoteSettingFetch` 取数，
   组合根委托给 `RemoteSettingRepository` 并在 di 侧完成 response 映射——
   这是 network 不依赖 repository/retrofit 的代价。
5. 认证请求编排归 `session`：`network/AuthenticatedEmbeddedClient` 只依赖
   `AuthenticatedRequestGate` 端口，组合根绑到 `session/AuthenticatedSessionGate`，
   network/session 互不 import。
6. `reader/ReaderImagePipeline`（L2）直接持有内存/磁盘缓存与解码适配器——
   缓存命中与解码结果决定请求是否进入后续流程，留在 L2 才能读出完整控制流。
   再包一层只会形成转发型假分层。
7. 缓存迁移调度：入队与状态观察都走 `cache/migration/CacheMigrationScheduler` 端口，
   由 `worker/CacheMigrationWorker` 执行。唯一构造 Worker、唯一解读 `WorkInfo` 的位置是
   `worker/WorkManagerCacheMigrationScheduler`——`CachePathViewModel` 因此既不 import `worker.*`
   也不 import `androidx.work.`。同名任务的历史记录不保证顺序，
   所以状态只从「未结束的那条」或「入队时记下的任务 id」认领，**认不出就不显示结果**。
8. 下载业务经 `download/DownloadWorkScheduler` 端口提交，不直接构造 Worker；
   实现以 **comicId 为粒度** `enqueueUniqueWork(..., KEEP, ...)`。
   `DownloadComicWorker`（26 行）只解析参数并调 `DownloadComicCoordinator`。

## 领域约定

### 玻璃弹窗

`GlassModal` / `GlassSurface` 只有组合在 `GlassCaptureHost` 的 **overlayContent** 里
才拿得到 `LocalGlassSurfaceRegistry` 并画 native blur；放在 source/页面本体会**静默退化成纯色块**。
组件内禁止自开 `GlassModal`（`ComicCoverImage` 经 `onShowDetail` 回调上提）。
自建 host 的页面弹窗必须组合在 topBar **之后**。

例外：收藏筛选 `FavoritesModalHost.FilterDialog` 刻意用 Material `ModalBottomSheet`
（密排 chip 时高斯模糊拉低可读性）；Welcome 引导无 host，走纯色 fallback。
阅读器 host 显式用 `GlassBackdropMode.Frosted`：半透明渐变磨砂 + 圆角细边框，
跳过底图录制与 `RenderEffect`，避免漫画大图下模糊失败导致外观切换——
**这是近似玻璃材质，不对背后的漫画做真实高斯模糊**。其他页面默认 `Blur`。

### 列表 Paging

搜索 / 收藏 / 历史 / 周推荐共用：`cachedIn(viewModelScope)` 必须写在 `flatMapLatest` **内**
（单 key 一代缓存，换筛选/文件夹/会话不串列表），外层再
`stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())` 挂上 VM 生命周期——
结果页进详情后 UI 取消收集，返回仍复用缓存。

搜索另用 `SearchComicFilter.revision` 做 UI `key`：`enterSearchResult` 同查询不换代，
`submitSearch` 强制 bump。搜索排除标签**只拼进查询串 `-tag` 交给服务端**，
`SearchComicPagingSource` 返回后不再做本地二次排除（旧实现逐条拉详情是搜索延迟主因）。
收藏文件夹切换、历史会话变化各自 bump 对应 filter/session 并同步 `resetGeneration` 视口代际。

### 封面加载

统一 `memoryCacheKey/diskCacheKey = jm-cover-{id}`；CDN 串行回退（`CoverImageHostResolver`）。
滚动中网络/磁盘结果延迟上屏（`PullRefreshAndLoadMoreGrid` 经 `LocalComicGridScrolling` 写入），
memory 命中仍立即上屏。封面专用 OkHttp：`Dispatcher` 全局 12 / 每 host 4，
与下载、阅读连接池隔离。

### 缓存额度语义

`cache/CacheBudget.kt`。哨兵 `UNLIMITED_MB = -1`；有限值 snap 到离散档
（512 MB / 1 / 2 / 4 / 8 GB，距离相等取较小档）。`LocalSetting.cacheBudgetMb` 类型仍是 `Int`，
`LocalSettingStorage`/`LocalSettingManager` 的 coerce 必须让 `-1` 穿透，**不得夹成 256**。

份额：COMMON 20% + READER 40% + OTHER 10%（DECODE/PDF 各半）+ DOWNLOAD 30%。
下载豁免时 DOWNLOAD 不占用户额度，原 30% **按比例并入**受控组件技术上限
（cover 20/70、reader 40/70、other 10/70），避免份额悬空。
**无限额度时**各组件磁盘 bytes 一律按 `MAX_TOTAL_MB` 算技术上限，
但 UI 与 `overBudget` **不**把该技术上限当用户额度——`overBudget` 在无限时恒 false。

API 契约：`*DiskCacheMb/Bytes(totalMb)` 入参是**总额度**；
`componentMbToBytes(componentMb)` 入参是**已分享额的组件 MB**，禁止二次套份额。
封面链路 `CoverImageLoaderHolder.coverDiskCacheMb(cacheBudgetMb)` → `Config.componentMbToBytes`。
历史字段 `LocalSetting.coverDiskCacheMb` 仅备份透传，不再驱动 Coil。

统计口径：命名分区 COMMON / READER / DECODE / PDF / DOWNLOAD，`ALL` 扫整个 `cacheDir`。
残差 = `ALL - 命名合计`（http_cache、updates 等）计入「其他」切片与额度已用。
`controlledUsedBytes` = 豁免时 `ALL - DOWNLOAD`，否则 `ALL`，与 `overBudget` 同口径。
`CacheCleanupScreen` **不 import `cache.*`**，档位与明细由 VM 以 `budgetStops`/`pieSlices` 注入。

### 阅读器链路

```text
UI / ViewModel / Download Worker
└── reader/ReaderImagePipeline         L2 优先级、去重、解码顺序
    ├── reader/coordinator/ReaderRemoteTelemetry
    ├── reader/molecule/ReaderSourceLoader     L3 来源缓存、CDN、回退与重试
    │   └── reader/ReaderRemoteFetcher         internal，远端获取与竞速
    └── reader/atom/
        ├── ReaderBitmapCache                  L4 解码图内存所有权
        └── ReaderImageDiskCache               L4 文件租约、代次、写入与清理
```

Reader 的 L3 不得依赖 UI 或 Worker，L4 不得反向依赖 L3。磁盘缓存把源文件与解码文件
留在**同一个原子**内，为的是清理时锁顺序和 cache generation 保持原子性；
拆成两个互相调用的 L4 会重新引入竞态。

**`reader/` 根包 16 个文件尚未归位**（共 22 个文件，仅 6 个在分层目录内），
绝大多数是 `internal` 的 Pipeline 私有协作对象：并发控制（`ReaderNetworkScheduler`、
`ReaderDynamicLimiter`、`ReaderConcurrencyPolicy`）、请求合并（`ReaderInFlightRegistry`）、
来源竞速（`ReaderRemoteFetcher`、`ReaderImageHostManager`、`ReaderHedge`）、
解码还原（`ReaderImageDecoder`、`ReaderScramble`）、预加载（`ReaderPrefetchPlanner`、
`ReaderPrefetchPolicy`、`ReaderLruCache`）、观测（`ReaderMetrics`）、
契约（`ReaderImageModels`）、UI 适配（`ComicPicImageStateReader`）。
粒度已足够小且可独立测试，**不列为迁移目标**；但它们不是分层目录的一部分，
读链路时别只看子目录。

### 其他

- 通用异步状态放 `core/model/CommonUIState`；启动后任务由 `startup/PostStartupCoordinator` 排序。
  冷启动静默检查更新走 `update/AutoUpdateChecker.checkOnce()`（失败静默，有新版本才写 prompt），
  UI 由 `App` 在 Glass overlay 里弹引导——`update` 禁 `ui.`。
- 导航基础设施 `ui/navigation/LocalMainNavController` 定义在 `ui/navigation` 而非 `ui/screens`：
  `ui/components` 与 `ui/glass` 都要读它，留在 screens 会让支撑层反向 import 页面包。
- 首页、搜索、周推荐各有独立 ViewModel；`HomeViewModel` 由首页与工具栏共享。
  搜索用 Activity 范围的 `SearchViewModel` 保持条件与滚动恢复。
  原 `ComicViewModel` 已移除，不保留转发型兼容外壳。
- 本地阅读由 `ComicReadViewModel` 调 `reader/molecule/LoadLocalChapter`，
  在 IO 线程组合下载记录、已完成章节与 `reader/atom/LocalChapterFiles`
  （目录查找、自然排序、旧 ZIP 解压均在文件适配器内，ZIP 先解压到临时目录、成功后提交）。
  ViewModel 以请求代次隔离迟到结果。
- `CacheCleanupViewModel` 持有扫描、额度档位、清理状态；`cache/atom/CacheFiles` 只管普通缓存目录。
  阅读器目录必须走原缓存代次/租约协议，不能被「全部清理」绕过。
- `DownloadExportViewModel` 持导出选择与统计请求代次；`download/export/DownloadExportOperations`
  负责文档授权、PDF 写入与文件统计。Screen 仅保留展示、导航、对话框和系统文件选择器。
- `BackupRestoreViewModel` 管备份/恢复步骤与任务生命周期；
  `backup/BackupRestoreOperations` 组合设置快照、文档读写和下载排队。
- `favorites/sync/FavoriteSyncController` 是**唯一**收藏同步任务入口，按登录会话代次隔离任务与结果；
  `favorites/usecase/SyncFavorites` 负责远端分页、元数据补齐与受会话保护的本地提交。
  冷启动由 `PostStartupCoordinator` 在会话就绪后自动发起，首页无需先打开收藏页。
  冷启动/进入收藏页/切换收藏夹共用**账号级冷却**：任意成功完成后 60 秒内自动请求直接跳过；
  完成时间存 Room 同步状态，重启仍生效；失败不进入冷却，不同账号独立计算。
  手动同步与强制刷新仍可立即发起，但与自动同步共用单个任务槽。
- `favorites/data/FavoriteStore` 保留 Room 事务与 DAO 操作，SQL 构造/同步规划/实体映射分别在
  `FavoriteQueries`、`FavoriteSyncPlanner`、`FavoriteMappers`，直接实现三个窄本地端口
  （`FavoriteLocalQuery`/`FavoriteLocalMutation`/`FavoriteLocalSync`），无转发对象。
- **本地模式（实验性）**：按账号存 `LocalSetting.localModeAccountIds`，
  `session/LocalModeGate` 合成只读模式状态。UI 文案统一标「实验性」——
  这是夜间账号风控下的妥协方案，不是长期产品形态。
  新增/取消收藏写为该账号待同步意图，`storage/LocalFavoriteChangeManager` 只在加密写入成功后确认。
  退出（`session/LocalModeCoordinator`）顺序：确认登录态（已手动登录则复用，否则重登）
  → 推送收藏意图 → `SyncFavorites` 全量刷新，**全部成功才**关闭该账号本地模式并清本地历史；
  期间 `LocalFavoriteOperationGate` 拒绝一切收藏修改（Collect/Uncollect/Move/建删改文件夹共用同一实例，
  构造注入、禁止默认新建）。失败则保留模式与意图供稍后重试。
  设置页确认后由 `worker/LocalModeExitWorker` 跑前台任务 + 常驻通知，离开设置或退后台不取消。
  强制对齐 `forceAlignFavoritesWithRemote` 同一顺序：relogin → 闸门内拉远端全量 → **成功后**清意图关模式。
  本地模式拦截抛 `core/network/LocalModeUnavailableException`（**不是**鉴权失败）。
  自动恢复从次日白天（06:00）开始，每晚最多引导一次。
- **分页协议字段严格解析**：`total` / 非空 `redirect_aid` 非数字时 mapper 直接失败（`toInt()`），
  不降级成 `0`/`null`——那会把协议畸形伪装成「只有一页 / 不重定向」。
  `redirect_aid` 为 null/blank 表示未命中。由 `ResponseMappersTest` 钉住。
  另：`App` 里包 `runCatching` 必须把 `CancellationException` 原样抛出。

## 安全与状态契约

这些不是实现细节，改动时需同步测试与本文（v1.4.3 起为架构不变量，v1.4.4 增补 7–14）。

1. **写确认后发布**（`storage/LocalSettingManager.updateSetting`）：内存状态只在
   `persistence.persist` 返回 `Success` 后前进；失败不得先 publish 再回滚假装成功。
   `SecureStorage.writeEncrypted` 必须用 `Editor.commit()` 并处理 `false`，不能依赖 KTX `edit{}` 的 `apply()`。
   `AppSecurityEditor`/`DohPreferencesEditor` 全部返回 `Boolean`；`DohManager` 仅在 persist 成功后改 runtime。
2. **备份格式 v4**（`backup/BackupManager`）：受保护备份不写无盐 SHA-256 凭据摘要；
   正确性只靠 PBKDF2 + AES-GCM。读路径接受版本 1–4；解密后的段用
   `BackupSectionResult`（Success / Missing / Corrupted）区分，**禁止把损坏降成空集合**。
   缓存段的 ID、章节顺序严格检查整数格式和范围，名字/作者/标签只接受字符串，
   畸形输入返回 Corrupted，不能从 UI 回调抛转换异常。
3. **下载组提交串行**（`download/molecule/DownloadContentOperations`）：
   同组 `complete()` 的「读快照 → 写索引 → DB 提交」用进程级组 Mutex 包住；只锁 `writeConfig` 不够。
   普通文件配置走临时文件 + `ATOMIC_MOVE`。
4. **OkHttpClient 构造清单**（`di/AppModule`）：应用自有客户端必须入册并设置 `DohManager` DNS；
   系统 DNS 仅允许 DoH bootstrap 例外。新增构造点必须更新清单并被 `AppHttpClientDoHTest` 扫到。
5. **历史会话归属**（`ui/viewModel/UserViewModel`）：历史分页与多选绑定
   `UserManager.sessionState`；会话变化立即清空展示与选择；删除/缓存入口校验选择所属会话，
   陈旧选择零提交。
6. **引导不得绕锁**（`App` / `LocalSettingManager.applyLocalSetting` / `WelcomeScreen`）：
   已有启用锁时 onboarding 不显示；恢复备份不得把 `onboardingCompleted` 打回 false，
   以打开可 `disableAndClearAppLock` 的引导路径。
7. **应用锁加锁时机与截图策略**（`App.kt` 的 `LifecycleEventObserver`）：启用应用锁时
   `ON_PAUSE` **或** `ON_STOP` 都要立即 `isLocked = true`；只依赖 `ON_STOP` 会让 recents
   在窗口冻结前拿到未锁定界面，切回前台闪现旧内容。
   `FLAG_SECURE` 只在 `isLocked` 为 true 时 `addFlags`、解锁后 `clearFlags`——
   锁定时禁止截屏，解锁后必须允许正常截图录屏，不能全程挂安全标志。
8. **内置 API 共享执行器**（`network/EmbeddedClientManager`/`EmbeddedTaskExecutor`）：
   候选客户端共用进程级 `clientExecutor`，`close()` **不得**关闭它
   （核心线程 30s 空闲回收，登出后不留常驻 SDK 线程）。SDK 在构造期就用 `execute()`
   启动异步初始化并抛 `JmComicException`，**必须在 execute 边界兜住**——
   异常逃出仓库层请求协程会直接杀进程；`submit` 的 future 仍向调用方报错，
   非 API 的编程错误保持原样传播。
9. **DoH 解析器发布顺序**（`network/DohManager`）：`ensureResolver()` 为 `@Synchronized`，
   且**先发布 `resolverKey` 再构造 TLS 客户端**；读者必须取同一把锁，
   否则会拿到新 key 配旧（可能为 `null`）的 resolver。
   `init()` 复用首屏已建 resolver，**不要用 `rebuildResolver()`**——那会关掉正在恢复阅读器的连接。
10. **自动恢复失败不得清除登录身份**（`session/UserManager`）：收藏遇认证失败时，
    在释放绑定会话锁后复用收藏同步的恢复通道，重登成功仅重试一次；历史记录经统一认证请求入口
    同样恢复。重试保留原账号和 generation，账号切换后不重放。
    连续 401 或密码错误文案**不能**排除持续的后端异常。
    自动恢复失败保留账号与 Cookie，返回 `Network` + `TemporaryFailure`；
    认证请求退避 30、60、120、240 秒封顶 5 分钟，冷却期内收藏/历史/签到不再发请求，公开接口不受影响。
    手动登录和退出清除冷却。
    **HTTP 401 或成功 HTTP 响应中明确的业务码 401 才触发恢复**，
    禁止凭错误文案或 FormBody 空参数异常判定失效；收藏错误映射须保留
    `SessionRecoveryException` 的分类，不能因嵌套的旧 401 再升级为「请登录」。
    冷启动只读探活独立于认证等待门，避免等待自身负责产生的就绪状态。
    独立登录客户端沿用活动客户端实际请求的可信域名，**仅继承该域的 `__cflb`，不继承 AVS**；
    凭据请求禁用 SDK 重试。`AuthAttemptOrigin` 只用于日志与密度观测。
    官方样本的通用请求层不因 401 自动重登，另有一小时本地认证有效期；
    这不能证明服务端 Cookie 的过期时间或风控规则。
11. **会话令牌单写者**（`network/EmbeddedSessionCookies`）：`AVS` 只允许由登录流程
    （`EmbeddedClientManager.activateCandidateSession`）写入持久化快照。
    登录身份、密码、JWT 与 AVS 在 `SecureCookieStorage` 中作为 startup preferences 的
    同一加密 `auth_session_v2` 记录提交；成功后才发布身份和持久化会话，
    提交失败恢复 preferences 内存映射及 SDK 暂存的 Cookie。
    JWT 按官方客户端的一小时本地有效期发送为 `Authorization: Bearer`，
    **且仅发到当前可信的 HTTPS API 域名**；域名下发等外部请求不带 AVS 或 JWT。
    旧版单独保存的 Cookie 可读取但不能证明账号归属；升级后首次联网用保存凭据验证一次，
    之后恢复只读探活。退出登录须确认 startup 撤销标记落盘，再清内存和旧记录；
    旧记录删除失败也不能复活会话，撤销标记不依赖 Keystore。退出写入失败保留当前账号并提示重试。
    响应侧合并必须走 `mergeEmbeddedResponseCookies`（剥离 AVS）——请求侧
    `embeddedCookiesForRequest` 在多个同名 AVS 之间按**列表顺序取第一个**，
    被公开响应污染过一次就会持续 401。
    登录候选客户端也在网络响应返回 CookieJar 前剥离 `Set-Cookie: AVS`，
    只保留 SDK 从登录 JSON `s` 写入的令牌，避免父域响应 Cookie 与登录令牌并存、请求选错。
12. **官方 API 签名**（`core/network/OfficialApiSignature`）：SDK 与 Retrofit 都使用官方
    JMComic3 v2.1.8 的 Token 密钥、版本和 `md5(timestamp + secret)` 算法。
    SDK 请求保留其原始请求时间戳以匹配内部响应解密时间戳；Retrofit 沿用进程固定时间戳。
    内置 API 的表单 POST 改用官方 multipart FormData，JSON POST 保持原类型。
13. **缓存相册屏蔽**（`cache/CacheMediaPrivacy`）：下载与迁移在应用创建的 `JM{编号}` 漫画目录
    先设 `.nomedia`，再写页面和封面；**不在用户选定父目录设置**。SAF 必须确认文件名没有被提供器改写。
    启动后补齐已保存和未完成下载的漫画目录；失败提示检查授权或换目录，下次启动重试。
    文件管理器仍可访问图片，旧相册索引可能需要刷新。
14. **诊断和系统备份隐私**：登录响应经严格 JSON 解析，递归替换敏感键值及协议加密的 `data` 字符串；
    畸形或非 JSON 正文只记省略标记。系统云备份和设备迁移排除 Room 数据库、导出日志、
    加密 preferences 与未携带授权的下载目录 URI；应用内手动备份流程仍可使用。

## 构建与密钥

- **语言级别**：Java / Kotlin bytecode **21**（`JvmTarget.JVM_21`）。
- **构建 JDK**：Gradle daemon 必须用 **Eclipse Temurin 21**
  （`brew install --cask temurin@21`）。`scripts/jdk-guard.sh` 编译前强制校验，
  Homebrew `openjdk@21` 仅作缺省兜底。**不要用 GraalVM 当 `JAVA_HOME`**——
  AGP 的 `JdkImageTransform` 会对 `core-for-system-modules.jar` 跑 jlink，
  而 GraalVM 的 `java.base` 仍依赖 `jdk.internal.vm.ci`，变换会失败。
  **不要**把本机 JDK 绝对路径写进 `gradle.properties` 的 `org.gradle.java.home`（会打挂 CI）。
- **工具链**：Gradle **9.7.1** / AGP **9.4.0** / KSP **2.3.12**。本地 `gradlew` 走 wrapper；
  需要 brew Gradle 时设 `JM_USE_LOCAL_GRADLE=1`。
- **签名密钥**：`release` 的 `storePassword`/`keyPassword` 只读环境变量，
  `eval "$(./scripts/android signing-env)"` 注入。`release-key/`、`*.p12`/`*.jks`/`*.keystore`、
  `.env*` 均在 `.gitignore`，**git 历史中从未出现过签名材料**。细节见
  [docs/release-signing.md](docs/release-signing.md)。
- **客户端协议常量**：`retrofit/ApiContext.kt` 的 `APP_DATA_SECRET` 是**写死在源码里的协议盐**，
  不是用户密钥也不是签名密钥。它会随 APK 一起被逆向，放进 BuildConfig/本地属性也无法保密；
  仓库内保留字面量是为了与线上 JM 协议兼容。
- **本地数据**：登录口令/会话经 `storage/CryptoManager`
  （Android Keystore `app_master_key`，AES-GCM）加密落盘，仓库不持有用户密钥。
