# 四层架构约束

项目增量采用 L1～L4 四层架构。目标是让功能入口、流程决策和底层实现容易定位，
而不是为了分层机械增加文件数量。尚未迁移的代码应被明确列为例外，不能只改目录名称。

> 本文描述**当前代码的真实状态**。与代码不符的措辞视为文档缺陷，应直接修正。
> 统计口径：`app/src/main/java/com/par9uet/jm` 下 `*.kt`，`find | wc -l` 汇总。
> 最近核对 **2026-10-20**（canary）：**432** 文件 / **54,461** 行。
> 耦合表用 `uv run scripts/check-coupling.py` 复现（细分口径，见脚本头）。

## 层级

| 层 | 职责 | 主要落点 |
| --- | --- | --- |
| L1 Entry | 只接收事件并交给 L2，不做业务判断 | `ui/screens`（`*Screen.kt`）、`ui/navigation`（含 `LocalMainNavController`）、`ui/components`（以参数与 `ui/models` 环境值为主，封面 loader/resolver 等单例由 Koin 默认值兜底）、`ui/interaction`（`rememberRestoringGridState` 等组合函数）、`MainActivity`、`App`（UI 组合根：提供环境值）、`worker/DownloadComicWorker`、`worker/CacheMigrationWorker`；平板布局 CompositionLocal 定义在 `ui/models/TabletLayout.kt`，由 `App` 调用的 `ui/screens/TabletLayout.kt` 的 `ProvideTabletLayout` 注入 |
| L2 Coordinator | 集中保存流程顺序、分支和跨边界协调 | `ui/viewModel`（**22** 个）、`reader/ReaderImagePipeline`、`reader/coordinator`、`download/coordinator`（含 `DownloadManager`）、`cache/migration` 的协调器与通知适配、`favorites/sync`、`favorites/presentation/FavoritesViewModel`、`session`（`UserManager`、`UserRepository`、`AuthenticatedRequestRecovery`、`SessionReadinessHolder`）、`startup`（启动任务与冷启动额度检查）、`notification/NotificationUnreadCoordinator` |
| L3 Molecule | 组合多个原子能力，完成一个完整业务动作 | `reader/molecule`、`download/molecule`（含 `DownloadLibraryQueries`）、`cache/molecule`（共享缓存清理）、`cache/migration` 的操作端口与实现、`favorites/usecase`、`backup/BackupRestoreOperations`、`download/export/DownloadExportOperations`、`repository/impl`、`dinner/DinnerRepository`、`notification/NotificationRepository` |
| L4 Atom | 每个原子只负责一个底层契约 | `database`、`storage`、`retrofit`、`data`、`network`（含内置 API 客户端与文章请求）、`image`、`coil`、`cache/atom`、`cache/CacheBudget`、`reader/atom`、`download/atom`、`download/export/PdfExport`、`favorites/data`（含 `FavoriteStore`）、`update`（含 `AppUpdateDownloadManager`）、`contentfilter`、`launcher`、`utils` |
| Shared Contract | 不含行为的稳定 DTO，可被各层依赖 | `core/model`（`CommonUIState`、`User`、`RemoteSetting`、`SignInData`）、`core/network`（`NetWorkResult` / `ResponseWrapper` / `AuthFailure` / `AuthenticatedSessionRequiredException` / `AuthAttemptOrigin` / `FormBodyNullParameter`）、`data/models`（`Comic` / `Comment` / `WeekData` / `ComicPage` / `CommentPage` / `ComicSearchPage` / `ComicPageList` / `ActionResult` / `HomeComicSwiperItem`；零出度）、`dinner/model`、`notification/model`、`favorites/model/FavoritesModels`、`reader/ReaderImageModels`、`download/model/DownloadLibraryModels` |

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
cache/                       atom/ L4、molecule/ L3、CacheBudget L4、migration/ L2+L3，另有未归位的 L4
ui/                          screens/ 之外：components、glass、navigation、theme、models（纯数据 +
                             环境值）、interaction（组合函数）、pagingSource、viewModel
session/ network/ storage/   L2 会话协调 / L4 网络设施 / L4 偏好与持久化
update/ backup/ contentfilter/ launcher/ startup/   扁平包，按类判断层级
core/                        共享契约与无依赖设施（含 AppHaptics 震动词表）
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
| `repository/ComicRepository.kt`、`RemoteSettingRepository.kt` | `retrofit.`；仓库公开契约只返回领域模型 |
| `retrofit/model/RemoteSettingResponse.kt` | `core.model.`；响应映射归 `repository/impl` |
| `core` | `ui.` |
| `ui/components` | `storage.`、`repository.`、`database.`、`session.`、`cache.`、`download.`、`backup.`、`update.`、`network.`、`reader.`、`favorites.`、`ui.viewModel.` |
| `ui/components`、`ui/glass` | `ui.screens.` |
| `ui/screens`（整体） | `com.par9uet.jm.cache.`、`com.par9uet.jm.download.export.`；**`getKoin` / `koinInject`**——依赖只能来自 Feature ViewModel 或 App 组合根参数 |
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
| `utils` | 领域包（`session`/`storage`/`download`/`reader`/`favorites`/`notification`/`cache`/`backup`/`update`/`repository`/`startup`/`dinner`）、`cache.`、`data.` |
| `image`、`contentfilter`、`launcher`、`database` | 同上领域包，外加 `network.`、`retrofit.`、`coil.`、`ui.` |
| `ui/viewModel`、`favorites/presentation`、`favorites/sync`、`session` | `ui.interaction.`、`ui.haptics.`、`ui.components.`、`ui.glass.`、`ui.screens.`（L2 不得反向依赖 L1 组合层） |
| `core` | 领域包与 `network.`（通用设施不横向引用；`ui.` 已另行禁止） |

`ui/models` 是 L1 里的**纯数据 + 环境值**子包（`ListPositionMemory` 与位置 key、平板布局
CompositionLocal、封面导航端口模型），不含业务判断，因此 `ui/viewModel` 可以依赖它清位置；
组合函数（`rememberRestoringGridState` 等）留在 `ui/interaction`，只有 L1 使用。

## 耦合现状

`uv run scripts/check-coupling.py`（**2026-10-20 / canary**）。
口径：模块 = 一二级包目录，**细分模块单列**（`core/model` 与 `core` 根目录分开、
`reader/atom` 与 `reader` 根包分开）；Ce = import 到的模块数，Ca = 依赖它的模块数，
I = Ce/(Ce+Ca)。下表只保留决策相关行，完整列表以脚本输出为准。

⚠️ **脚本口径 ≠ 递归总数**：`core` 行只算根目录 4 文件/210 行（`core/model`、
`core/network` 另列）；`reader` 行只算根包 15 文件/2100 行。要总数得自己 `find -name '*.kt'`。

| 模块 | 文件 | 行数 | Ce | Ca | I | 判断 |
| --- | --- | --- | --- | --- | --- | --- |
| `di` | 12 | 890 | 35 | 1 | 0.97 | 组合根，合法 |
| `ui/screens` | 52 | 18,092 | 23 | 1 | 0.96 | 最大一块；直连已清零，见「表现层直连」 |
| `ui/viewModel` | 22 | 4,479 | 32 | 3 | 0.91 | L2；扇出最高但多为契约与偏好 |
| `ui/components` | 28 | 2,948 | 13 | 2 | 0.87 | 已收窄，无 L3/L4 领域依赖 |
| `favorites/presentation` | 2 | 537 | 8 | 2 | 0.80 | L2（对 `ui.models` 的依赖见下） |
| `favorites/data` | 14 | 1,261 | 10 | 3 | 0.77 | Room + 窄端口 |
| `reader`（**仅根包**） | 15 | 2,100 | 8 | 6 | 0.57 | 根包说明见「阅读器链路」 |
| `network` | 19 | 2,073 | 5 | 11 | 0.31 | DoH / RemoteConfig / 内置客户端 |
| `storage` | 17 | 2,427 | 5 | 15 | 0.25 | 偏好与持久化收口 |
| `core`（**仅根目录**） | 4 | 210 | 2 | 17 | 0.11 | `BaseRepository`、`ToastManager`、`AppHaptics`（只依赖 Android 震动 API） |
| `core/model` | 5 | 101 | 0 | 13 | 0.00 | 共享 DTO，**真正的零出度** |
| `ui/models` | 6 | 199 | 1 | 7 | 0.12 | L1 纯数据 + 环境值，L2 仅引用其中的位置数据 |
| `utils` | 14 | 839 | 1 | 28 | 0.03 | 稳定，不要动 |
| `data`（= `data/models`） | 16 | 416 | 0 | 24 | 0.00 | 零出度契约，不要动（`data/comic` 另列） |
| 根包（`App` / `MainActivity` / `JmApplication`） | 3 | 760 | 1–12 | 0 | 1.00 | 入口 |

`ui/screens` 内最大文件：`ComicDetailScreen` 1,397、`LocalSettingScreen` 830、
`ComicReadScreen` 795、`BackupRestoreScreen` 781、`FavoritesModalHost` 734、
`CheckUpdateScreen` 694。**这些是可读性问题，不是跨层耦合**——不再按行数机械拆分。
`DownloadComicDetailScreen` 已经由 ViewModel 取得下载与导出状态，不再列为跨四层的待迁移页面。

### 不建议动

- **`data` / `utils` / `database/model` / `retrofit/model` / `core`**：Ca 高但 Ce 极低，
  是被依赖的稳定契约，拆分只会制造转发层。
- **`reader` 根包文件**：粒度已足够小且可独立测试，下沉只增加目录层级。
- **`di`**：高扇出是组合根的应有形态。
- **`ui/viewModel` 读 `storage` 偏好**、读 `cache.atom` 1 处（`CacheCleanupViewModel`）
  与 `cache/migration` 2 处：属 L2 选定适配器与既有偏好惯例，**不算待修范围**。
- **`network` / `retrofit` / `coil` 读 `storage` 的窄偏好端口**（`DohPreferences`、`CookieStorage`、
  `ApiEndpointPreference`、`RemoteConfigPreferences`）**与 `cache.CacheBudget`**：同为纯数据/额度契约的同层设施，
  不构成反向依赖；它们仍不得 import 任何领域包（由边界测试钉住）。
- **`favorites/sync` 经 `session.withAuthenticationRecovery` 复用认证恢复**：与 `favorites/usecase`
  同一处调用，属 L4→跨域 L2 的有意复用，不再单列待办。

### 表现层直连：已清

`uv run scripts/check-coupling.py <模块>` 可复现。「违规」指跨过 L2 直读 L4 设施，
或反向依赖上层；`data.models` 是共享契约，不算违规。

- **`ui/components`**：出边只有 `ui/*` 同层设施、`coil`、`image`、`utils`、`BuildConfig`
  与 `data.models`，**无 L3/L4 领域依赖**。
- **`ui/viewModel`**：`retrofit/model` 已清零，无 `favorites/data`、无 `database`。
- **`ui/screens`**：`download/coordinator` 2、`repository` 1 已消除；`storage` / `network` / `cache`
  **已清零**（早期口径曾是 23 / 7 / 1，随 `getKoin` 收口下沉 `ui/viewModel`）。
  仍直连 `contentfilter`（搜索语法/标签排除，契约向）、`backup`、`session`
  （全部为 `SessionReadiness` 枚举，**无 `UserManager`**）、`favorites/presentation`、
  `download/model`、`update`、`reader` 契约类型。

Screen 的 `session.UserManager` 直读已经清零，并由边界测试固定；鉴权状态经 ViewModel 暴露。
`favorites/presentation.FavoritesViewModel` 是 L2，Screen 直接持有属合法 L1→L2。

## 已收工的迁移（结论，勿重复劳动）

以下工作已完成且由边界测试钉住，**不需要再排期**：

- **`store` 包清空删除**（2026-09-12）：`UserManager`/`UserRepository`/
  `AuthenticatedRequestRecovery`/`SessionReadinessHolder` → `session`；
  `FavoriteStore` 及其 SQL 侧 → `favorites/data`；`RemoteConfigManager` → `network`；
  `LocalSettingManager` 及各种偏好 → `storage`；`BackupManager` → `backup`；
  `ToastManager` → `core`；共享 DTO → `core/model`。
- **L2 反向依赖 L1 清零**（2026-10-20）：`ui/interaction/ListPositionMemory` 一拆为二——
  纯数据与位置 key（`home` / `search|<queryKey>` 等约定）下沉 `ui/models/ListPositionMemory.kt`，
  只留 `LocalListPositionMemory` 与 `rememberRestoringGridState` 在 `ui/interaction`；
  `ui/haptics/AppHaptics` 移到 `core/Haptics.kt`（只依赖 Android 震动 API，除 `core.model` 外零出度）。
  修掉的是 `ui/viewModel/SearchViewModel`、`favorites/presentation/FavoritesViewModel`
  为「清位置」「删除震动」反向 import L1 组合层的两条边，并由边界测试钉住
  「L2 不得 import `ui.interaction` / `ui.haptics` / `ui.components` / `ui.glass` / `ui.screens`」。
- **5 个历史依赖环消除**：`data↔repository`、`data↔retrofit`、`retrofit↔store`、
  `repository↔store`、`data↔reader`，以及评审发现的 `network↔session↔retrofit` 三边环
  （修法：`AuthenticatedSessionRequiredException` 下沉 `core/network`，
  `network` 定义 `AuthenticatedRequestGate` 端口由组合根绑到 `session/AuthenticatedSessionGate`，
  `Retrofit.kt` 参数改 `okhttp3.Dns` 由组合根注入 `DohManager`）。
  随之消除 `session→data`、`network→repository`、`network→data`。
  legacy L4（`storage`/`data`/`network`）仍有待处理 SCC，后续迁移**先抽窄端口再切断反向 import**。
- **`ComicRepository` domain-only**：`toXxx()` mapper 下沉 `repository/impl`，
  wire DTO 不再渗到表现层。分页元数据用 `data/models` 的 `ComicPage`/`CommentPage`/`ComicSearchPage`
  表达（`ComicPage.total` 可空；远端历史直接返回 `List<Comic>`，不使用分页元数据）。
  **剩余 legacy boundary**：`UserRepository` 仍经 `CandidateSession` 暴露
  `LoginResponse`/`okhttp3.Cookie`。
- **`RemoteSettingRepository` domain-only**：返回 `core/model/RemoteSetting`，wire 响应
  在 `repository/impl` 就地映射，DI 只绑定取数端口；`RemoteSettingResponse` 只保留实际读取的
  `img_host`，不再携带未消费的分流数据或领域转换方法。
- **下载 4 个 UI 文件直连 Room**：`ui` 整体禁 `database`，DAO 观察收敛到
  `download/molecule/DownloadLibraryQueries`，UI 用 `download/model` 契约。
- **`ui/components` 收窄**：`LocalMainNavController` 归位 `ui/navigation`；
  远端图片主机/详情预置/详情取数改 `ui/models` 环境值 + 组合根提供；
  `ComicPicImage` 移入 `ui/screens/readScreen`；`ComicLazyGrid` 屏蔽标签改为入参。
- **导出模式归位 `download/model`**：`PdfExportMode` 曾是 `download/export` 的实现细节，
  Screen 直接 import L3 包；移到共享契约后，边界测试钉住「`ui/screens` 禁 `download.export.`」。
- **设置页本地模式依赖收口**：`LocalSettingScreen` 不再 `koinInject` 协调器/闸门/调度器；
  经 `SettingsViewModel` 与 `di/LocalModeSettingsAdapter` 暴露 `LocalModeSettings` 窄端口，
  Screen 只收集状态、发动作。边界测试同时禁 `getKoin` 与 `koinInject`。

## 有意保留的例外

以下几处跨层是**刻意设计**，不要"顺手修正"：

1. 下载协调器直接用 DAO 与反馈适配器——进度与同组任务状态决定通知和终态分支，拆散反而更难读。
2. 更新协调器直接用下载/安装端口，备份协调器直接用校验/提取能力——结果直接决定流程分支，
   不为它们加只做转发的用例层。
3. 缓存迁移协调器经 `CacheMigrationDownloadGate` 协调下载；共享清理 `cache/molecule/CacheCleanupOperations`
   经组合根注入封面/阅读清理与停下载回调，不 import Coil 或阅读/下载协调器。导出协调器直接调 PDF 文件端口。
   跨功能资源生命周期由显式端口交接，L4 不得反向调用。
4. `RemoteConfigManager` 经 `network` 包内窄端口 `RemoteSettingFetch` 取数，
   组合根委托给 `RemoteSettingRepository`，response 映射在仓库实现内完成——
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
9. `ui/components` / `ui/theme` 的封面 loader、resolver、Toast 与外观偏好仍以 `getKoin()`
   默认参数兜底：单例由组合根提供，组件不 import 领域实现；去掉默认值会让独立组合与
   预览必须手工注入。新增组件默认值前先确认它不引入 L3/L4 领域 import。

## 领域约定

### 玻璃弹窗

`GlassModal` / `GlassSurface` 只有组合在 `GlassCaptureHost` 的 **overlayContent** 里
才拿得到 `LocalGlassSurfaceRegistry` 并画 native blur；放在 source/页面本体会**静默退化成纯色块**。
组件内禁止自开 `GlassModal`（`ComicCoverImage` 经 `onShowDetail` 回调上提）。
自建 host 的页面弹窗必须组合在 topBar **之后**。
面板在当前窗口的系统栏及顶栏之外布局，全屏遮罩继续拦截背景触摸；章节列表先让标题/底部动作占位，再使用剩余高度。
调色面板以 `LocalDensity` 转换 window 像素；宽度上限在 fillMaxWidth 之前生效，避免旋转后被固定满屏宽度覆盖。

例外：收藏筛选 `FavoritesModalHost.FilterDialog` 刻意用 Material `ModalBottomSheet`
（密排 chip 时高斯模糊拉低可读性）；Welcome 引导无 host，走纯色 fallback。
阅读器 host 显式用 `GlassBackdropMode.Blur`，与其他页面采用同一真实模糊路径；
不根据加载/失败/前后台状态切回旧的 `Frosted` 模式。API 30 无 `RenderEffect`，
玻璃层保留半透明底色，这是平台降级路径。

### 详情页与加载反馈

详情页标签加载骨架统一为 **一行 / 32dp**，完整骨架与列表种子数据补全共用同一组件。
平板横屏骨架的标签位于右侧元数据列；竖屏/窄分屏和手机位于封面头部下方。真实标签从一行开始逐行展开，
高度按实际芯片测量结果过渡；标签内容变更即使行数相同也须重启动画，不能留下未显示的行。
平板骨架与正式封面共用 `ui/screens/TabletLayout.tabletDetailCoverWidth`，以当前窗口宽高和上下操作区
计算 3:4 封面槽位，宽度最多 392dp，右列使用剩余空间；不用权重覆盖封面上限。
介绍与标签/评论各自使用既有内容动画层；手机和平板共用封面转场与侧滑确认后的完整退场控制器。
平板仅在**横向且能同时放下封面与 420dp 阅读列**（`useDetailColumns`，即宽 ≥ 1120dp）时使用双栏；
其他情况改为最大 840dp 的居中阅读列，紧凑封面最多 240dp，标签/评论完整占据阅读列。
只看「宽 > 高」会把浏览器式分屏的半屏横窗误判成双栏，右侧内容列被挤窄、页面右缘留整列空白。平板底栏为两组玻璃按钮，横竖屏均分居左右两角，手机仍是单条底栏。
`ComicDetailAdaptiveLayout` 保留同一份封面/元数据/标签评论，按 320ms 贝塞尔分别过渡真实布局尺寸和位置；
背景持续可见，不淡出整页、不重建 Glass host。底栏和右上按钮锚定新窗口边缘；封面/内容列的中间帧也受新窗口边界约束，原生玻璃跟随实际布局坐标。
滚动内层显式满足视口最小高度；普通/下载详情统一顶部对齐，不再根据评论加载状态和内容长度居中整组内容，
短内容仅在页面下方自然留空。下载详情顶部控件避让后保留 8dp 间隔；平板横竖屏均保留同一份封面/介绍/正文，不切回手机满宽封面。
平板下载详情仅显示左上角玻璃返回按钮，复用普通详情的按钮尺寸与退场行为；本地/远程回退封面及其转场层采用相同 medium 圆角。
横屏沿用左封面/右内容，竖屏封面最多 240dp、介绍居右、标签/缓存状态占下方完整阅读列，组件位置和尺寸以 320ms 连续过渡。
下载详情的模糊背景和左右两组玻璃操作也保持同一组合：横竖屏都为左「阅读」/ 右「章节·导出」，格宽平滑过渡且受当前窗口限制。
横竖屏共享外层滚动与评论网格状态；连续旋转从当前值接续目标，首次/关闭动画/封面进退场时直接使用目标尺寸。
MainActivity 自行接收 orientation / screenSize / smallestScreenSize / screenLayout 变更，Compose/View 随新配置重排，
其他配置变更仍可由系统重建；旧尺寸详情任务和返回覆盖层释放，已提交返回只完成一次。
平板空白区不能发起直接整页拖动，直接拖动起点须位于真实封面内；滚动容器的触边接管保留。
详情与阅读器的收藏按钮统一为空心/实心心形，复用 `ui/interaction/FavoritePulse`。
零观看量时，`ui/components/ComicMetrics` 将观看/喜欢文本同时显示为 `-`，保留原始模型值；详情信息弹窗共用此显示规则。
食堂原图的 `ui/interaction/ImageViewportTransform` 按 Fit 后图像与视口尺寸限制平移，并在旋转后重新约束。
动画裁剪以当前外层高度为准，不以完整子内容的高度裁剪。失败后没有标签则移除占位。
详情响应映射时过滤 tags / actors / works 中的空串与纯空白；`ComicTagSection` 对已有缓存也做同样防护，
不能把上游占位值画成可点击的空芯片。JM1468134 的 tags 实测含 4 个有效词与 1 个空串，回归测试保留该形状。
详情（含缓存详情）作者为空、纯空白或仅 `N/A` 时显示灰色「未知作者」，不提供搜索点击；有效作者保持原有搜索。

阅读器章节与单页加载均用 `ReimuLoadingIndicator`（本地 GIF），不存在回退到默认转圈的状态分支。
首次构建收藏缓存的 GIF、提交操作的转圈、页尾的追加加载指示器分别表示实际任务，保留各自语义。

### 首页推荐恢复

首页右上角「更多」玻璃菜单按图标与两个汉字收紧为默认 128dp，大字体时按文字大小增加宽度；
手机和平板共用，其他菜单保持各自宽度。

`HomeViewModel` 保留推荐请求的失败状态，初次 `/promote` 失败或没有可用内容时先显示内置分类，
不把空推荐缓存为本次进程的成功结果。`HomeScreen` 只报告生命周期 STARTED 与首页 Tab 是否可见，
重试由 ViewModel 按 **2 / 5 / 15 / 30 / 60 秒**退避，此后间隔保持 60 秒，直到取得可用推荐。
离开首页或进入后台取消待执行重试；重新进入可见首页时，仍缺推荐则立即补取，在途请求不重复启动。
恢复后补回推荐栏目，保留当前分类及已有内容，不让重试过程重建整页骨架。
网络推荐与自动签到在生产设置中统一开启，设置页不再提供开关。`LocalSettingManager` 在加载、导入和
后续写入时归一化旧关闭值，DTO 保留旧 JSON 字段以兼容备份；设置 ViewModel 不再维护对应开关状态/写入口。
已加载过有效推荐时，暂时空响应保留最后一次可用栏目与内容。
网络推荐在 `ComicRepositoryImpl` 映射成漫画前排除栏目 ID `1001`（书库）和 `1002`（小说），
避免非漫画载荷进入详情/阅读链路；其余栏目顺序、标题与前两项换位规则保持不变。

### 列表 Paging

搜索 / 收藏 / 评论历史 / 周推荐共用：`cachedIn(viewModelScope)` 必须写在 `flatMapLatest` **内**
（单 key 一代缓存，换筛选/文件夹/会话不串列表），外层再
`stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())` 挂上 VM 生命周期——
结果页进详情后 UI 取消收集，返回仍复用缓存。

搜索另用 `SearchComicFilter.revision` 做 UI `key`：`enterSearchResult` 同查询不换代，
`submitSearch` 强制 bump。搜索排除标签**只拼进查询串 `-tag` 交给服务端**，
`SearchComicPagingSource` 返回后不再做本地二次排除（旧实现逐条拉详情是搜索延迟主因）。
收藏文件夹切换、评论历史会话变化各自 bump 对应 filter/session 并同步 `resetGeneration` 视口代际。

`PullRefreshAndLoadMoreGrid` 顶部刷新指示器只响应用户下拉；Paging 首次订阅、页面恢复及
后台刷新不得自动冒出下拉转圈。已有条目继续展示；刷新结束或失败时清除手势刷新状态。

### 评论过滤

设置入口 `commentFilter` 的 `CommentFilterScreen` 沿用排除模板的说明卡片、输入卡片与关键词芯片样式，
经 `SettingsViewModel` 增删关键词，不直接访问存储。`LocalSettingManager` 是单一写入者，
通过 `ContentPreferences.blockedCommentKeywords` 暴露窄状态流，持久化到既有设置 JSON 并随设置备份。
默认词为「扣群 / 加群 / VIP / 可约 / 一对一」，旧设置缺字段或 null 回填默认；显式空列表保持为空，表示不过滤。
写入时去首尾空白、忽略大小写去重，失败不发布新状态，界面保留添加草稿供重试。

`contentfilter/CommentKeywordFilter` 是本地纯过滤原子：以 Jsoup 解出可见正文，任意词子串命中即隐藏，
忽略英文大小写，不匹配用户名或 HTML 属性；干净主评论下的回复递归过滤，主评论命中则整个讨论串隐藏。
`ComicDetailViewModel` 与 `UserViewModel` 在原始 `cachedIn` 分页流之后 combine 关键词，再过滤主评论与回复。
关键词变化不重建 API 分页，删词可恢复缓存中的隐藏评论；不修改远端 total/页码，整页被过滤仍能继续取后续页。
对应测试覆盖默认词/HTML/嵌套回复、持久化和空列表兼容、实时恢复与无额外请求、整页过滤后的续页。

### 历史浏览

经实际核对，官方 App 的远端历史至多显示最新 20 条，接口没有可用的后续翻页。
`UserRepository.getHistoryComicList()` 只请求一次 SDK `getWatchHistory(1)`，返回 API 的完整列表；
不按显示条数补齐、不预取、不解析分页总数，也不在客户端额外截断。
`UserViewModel.HistoryState` 持有列表、加载与错误状态；每次进入历史页或从后台返回，
`LifecycleStartEffect` 重新获取最新历史。普通网格不提供上拉翻页、下一页按钮或末页提示。
换号/登出立即清空列表与选择并取消在途请求；响应同时校验请求代次和会话快照，
阻塞式请求的迟到结果不得覆盖新账号。

`watch_list` 可能包含**章节 id**：读合集里的第 N 话后，服务端历史条目可能是那一话的 id。
本机 `ReadHistoryManager` 在打开合集详情时记录合集元数据，并在阅读时把章节 id 归到合集 key
（`seriesId` 优先，缺省用当前页面的合集 id）；同时保存完整目录与合集的关联，目录不计入已读章节。
历史页展示前用这份本机记录把章节条目换成合集卡片，
封面/标题/作者/标签因此指向原合集。删除仍按服务端章节 id 提交，下载仍取服务端章节；
阅读路由带 `historyId`（父合集 id），`ComicReadHistory.lastChapterId` 保留具体章节用于续读。
旧进度只有父子 id、缺少合集标题时不伪造合集卡片，保留章节条目并通过 `seriesId` 指定合集封面；
`ComicCoverImage` 对有效的 `seriesId` 优先取合集封面，否则使用条目自身 id。

合集去重只影响展示数量：20 条章节记录合并后可能只显示 19 张卡片；
删除合并卡片时提交其对应的全部原始章节 id，不用合集 id 替代服务端历史条目。

### 绅夜食堂

L1：`DinnerScreen` / `DinnerArticleScreen` 接收点击并导航；首页右上角二级菜单入口名为「食堂」。
文章列表用 `LazyVerticalStaggeredGrid` 按真实图片比例交错排布，标题最多三行、页尾状态整行显示。
列数沿用首页设置，`adaptiveComicStaggeredGridCells` 与普通网格共用固定/自适应判定及
`comicGridMinCellSize`（手机 118dp / 平板 160dp）；左右留白和列间距同为 12dp。
下载中心完成列表的非 Lazy 行网格也直接调用共享 GridCells 的像素分配结果，按实际内容区域重算列数，不再用窗口宽度硬分成 2/3/4 列。
图片比例按 URL 保存在 `DinnerViewModel` 并作为图片初始比例，另保留图片本身的 saveable 状态；painter/位图不写入保存状态。
列表位置复用 `ListPositionMemory` 的 `dinner` 键，通过框架 Saver 保存各列索引/偏移；
`GlassCaptureHost` 显式转发记忆容器，避免独立 ComposeView 重建后丢位置，不保存状态/View/布局对象。
封面（含加载中或失败）与标题共用文章导航。列表触底自动加载，等待新页完成布局再检查；
加载中防重入，失败停止并显示重试，整页过滤为空可继续取后续页。
L2：`DinnerViewModel` 保存列表/正文加载、分页重试、连接模式和在途代次，并暴露窄偏好的列数状态，禁止迟到文章覆盖当前选择。
L3：`dinner/DinnerRepository` 组合原始协议映射、推广过滤、按需详情补查与正文解析，只对 UI 暴露 `dinner/model`。
L4：`network/EmbeddedDinnerApi` 复用 SDK 网络传输；`dinner/atom/ArticleHtmlParser` / `ArticleLinks`
将 HTML 和裸 JM 编码、文章链接变成原生图文块及导航目标；`ArticlePromotionFilter` 检查可见推广文案及已确认链接，不执行网页脚本。公共展示组件只依赖模型和
既有 Coil/图床适配；不访问仓库。推广过滤保留远端原始条数和总量，不能用显示条数误判末页。
取消作者白名单：无署名、未知作者和缺少列表正文不触发过滤，不将上传者展示为作者，正文原有署名保持原样。
列表直接检查 `content` / `game_url`；仅存在导流提示但尚未确认的文章补查详情链接（限时 3 秒）。
成功的判断在仓库内缓存 10 分钟、最多 128 条，正文/入口变化失效；补查失败保留卡片、不缓存失败，取消向上传递。
详情始终重新校验完整正文，确认推广则移除当前列表条目。按实际 URL host 边界及已确认短链路径匹配，不一律屏蔽外链，不追踪重定向。

文章图片共用 `CoverImageLoaderHolder` 及 `CoverImageHostResolver.articleUrls` 的全局图床排序、DoH、
健康冷却、缓存预算与串行回退；标准 JM 封面复用 `jm-cover-{id}`，外站图片保留原始地址。
阅读器 GIF 已提升至 `ui/components/ReimuLoading.kt`，由阅读器、文章和通知统一引用。
协议字段、推广文案/链接策略和链接行为见 [docs/dinner.md](docs/dinner.md)。

### 漫画追踪与通知

L1：合集详情 JM 编码下的订阅按钮、首页右上角铃铛与 `NotificationScreen`。
L2：`ComicTrackingViewModel` 判断合集、查询/切换订阅并复查状态；`NotificationViewModel` 管理
通知分类、分页、展开和已读。SDK 为单本补出的自身章节不视为合集。
L3：`NotificationRepository` 组合官方协议映射、追踪数组与 HTML 解析，以及全页一键已读。
L4：`EmbeddedNotificationApi` 通过认证客户端执行带官方签名的 GET/POST；共享网络与账号会话。
通知模型位于 `notification/model`，正文复用 `dinner/model.ArticleBlock`。

`session/AccountRequestScope` 由 DI 适配到 `UserManager.withBoundRemoteSession`；所有通知与订阅读写
绑定发起时账号及 generation，切号立即清空，迟到响应不能落入新账号。订阅是服务端切换操作，
POST 结果不明时只重查状态，不自动重放。通知点击只设为已读，不切换为未读。
一键已读先获取全部分类/全部页，再逐封提交；重复页中止处理，不猜测未验证的批量 API。
`NotificationUnreadCoordinator` 通过 `NotificationUnreadStatus/Updates` 向首页暴露未读状态，
通知成功已读后本地递减/清空；账号变化清空旧状态，迟到数量不能重新点亮已读提示。
冷启动的 `notifications/unreadCount` 只调用一次，等待其余启动任务、`StartupHomeLoad` 记录的首页首批请求（含失败降级）和异步收藏同步完成后最后执行，
失败静默；后台返回与首页重组不轮询。首页铃铛有未读时变红；通知分类菜单改由标题点按触发。
正文使用既有文章渲染、CDN/缓存、GIF 与站内导航；不套用绅夜食堂广告过滤。
交互及协议细节见 [docs/notifications.md](docs/notifications.md)。

### 交互动效

`ui/models/CoverNavigation` 定义封面入口、分层进场与详情关闭端口；`ui/navigation/CoverNavigationHost`
在点击时等待来源页面 RESUMED、绘制刷新后重新取得来源矩形和内存背景，以条目生命周期管理。`ui/interaction/CoverDetailTransition`
包裹远端/缓存详情：封面 GraphicsLayer 从来源矩形移动到真实布局报告的目标矩形，背景/玻璃控件
按同一 448ms 进度自顶向下铺满，再用 224ms 显示介绍与标签/评论两个内容层。
`DetailEntryLayers` 只负责绘制记录、目标测量和分层透明度；`GlassCaptureHost` 显式跨 ComposeView
转发详情端口、封面入口和页面标识/可见封面注册表。进场与退出均独立绘制封面；
退出先退内容（约 150ms），再将背景/玻璃与封面分层记录为内存位图，交给 NavHost 上方的
`CoverReturnOverlay` 持有。弹出后依据真实上一页的当前可见封面确定终点，反向收回背景（256ms）；
没有目的地或目的地中途被移除时，封面从当前位置改走屏幕中心并淡出。位图随退出完成释放，
不持有已弹出详情的组合或 GraphicsLayer，不请求网络。拖动/440ms 回弹沿用页面变换；
系统侧滑由 `HierarchicalBackHandler` 消费进度，期间详情不变，取消不触发动画或截图；
确认后与左上角按钮共用 `DetailMotion.dismiss`，完整执行内容→封面/背景退场，不由侧滑幅度/速度决定阶段。
仅在内容隐藏后准备一次位图，已删除跟手预览、手势预取与取消恢复状态。进场与竖向拖动回弹使用三次贝塞尔，
返回退场使用线性进度。`CoverReturnPath` 在落点变化时从上一帧接续，
保留当前帧位移与原结束时间，忽略不超过 1 个物理像素的落点抖动；
遮罩与输入派生状态不触发逐帧整页重组，封面使用浮点绘制。若详情封面已滚出屏幕，
只回收背景并显露上一页原位封面，不复制离屏封面。可见的末段飞行至少保留 160ms。
导航页面的截图记录位于独立 GraphicsLayer，返回覆盖层为同级绘制，避免其进度变化触发整页重新记录。
已提交退场保持输入独占：详情控制器忽略新拖动与重复关闭，覆盖层同时消费普通触摸与系统预测返回，
防止上一页 NavHost 被二次侧滑驱动、移动目标封面并重算路径；完成后释放拦截。
`BlockDetailDrag` 在页面组合时读取弹窗/评论状态，跨 Glass 子组合变化立即锁住整页拖动并取消已有拖动。阅读与章节导航沿用原流程，
返回已展开详情时不重播。NavHost 对进入/退出详情不再叠加水平转场。
无来源矩形/快照的编码或链接入口立即显示详情骨架，不播放展开；有来源时，真实 painter 未就绪前在封面图层内
保留原缩略图，不把占位图层当成已加载图。原图仍参与滚动、裁剪、模糊和返回快照；目标就绪时直接交接。
网格列数弹窗将 window 像素转换为 dp 后限高，并避开顶部锐利控件层，防止玻璃背景下的顶部栏文字重叠。

V3 按压由 `PressFeedbackIndication` 与公共 `MotionButtons` 适配既有交互，保留 ripple/点击/热区，
以 120ms 缩到 0.96、200ms 恢复；这些适配属于 L1，不承担收藏或网络业务。
`ProvideScrollEdgeFeedback` 在主题层包装系统 `OverscrollFactory`，`GlassCaptureHost` 将其显式转发到
独立 ComposeView；在实际系统回弹中观察用户拖动/惯性的上下碰撞，通过 `CLOCK_TICK` 轻震。
保留原节点、滚动消费与绘制，同一次拉伸只震一次，回弹结束后再次外拉可重新触发；程序滚动静默。详情收藏图标的一次放大回弹与真实收藏状态分离。
具体参数、降级与测试见 [docs/ui-motion.md](docs/ui-motion.md)。

### 剪贴板检测

`App.MainAppContent` 在自动检测开关开启、生命周期回前台时读取剪贴板；仍按数字编码校验和原有仓库加载漫画。
取消/系统返回/外部关闭有效预览时，App 将请求编码与返回漫画 ID 交给 `session/ClipboardDetectionSession`。
它由 `di/AppModule` 以进程单例提供，仅保存被取消的编码集合：文本不同、前导零、切后台、应用锁或 Activity 重建不重置，
新进程冷启动才重新允许提示；不落盘、不屏蔽其他编码，不把失败请求或正常跳转标记为取消。
检测前过滤可避免再次发起同一编码的网络请求；预览保留对应请求 ID，避免异步结果与当前检测 ID 混淆。

### 封面加载

统一 `memoryCacheKey/diskCacheKey = jm-cover-{id}`；CDN 串行回退（`CoverImageHostResolver`）。
滚动中网络/磁盘结果延迟上屏（`PullRefreshAndLoadMoreGrid` 经 `LocalComicGridScrolling` 写入），
memory 命中仍立即上屏。封面专用 OkHttp：`Dispatcher` 全局 12 / 每 host 4，
与下载、阅读连接池隔离。
同一本漫画的已显示封面不随 CDN 候选地址变化而清空，网络恢复时等替代图片就绪再过渡；
漫画 id 变化仍清空，避免沿用另一本漫画的封面。

### 缓存额度语义

`cache/CacheBudget.kt`。哨兵 `UNLIMITED_MB = -1`；有限值 snap 到离散档
（512 MB / 1 / 2 / 4 / 8 GB，距离相等取较小档）。`LocalSetting.cacheBudgetMb` 类型仍是 `Int`，
`LocalSettingStorage`/`LocalSettingManager` 的 coerce 必须让 `-1` 穿透，**不得夹成 256**。

份额：COMMON 7/30 + READER 14/30 + DOWNLOAD 30%。
下载豁免时 DOWNLOAD 不占用户额度，原 30% **按比例并入**受控组件技术上限
（cover 7/21、reader 14/21），避免份额悬空。
**无限额度时**各组件磁盘 bytes 一律按 `MAX_TOTAL_MB` 算技术上限，
但 UI 与 `overBudget` **不**把该技术上限当用户额度——`overBudget` 在无限时恒 false。

历史上还有第三个受控分项 OTHER 10%（`pic_decode` 与 `pdf_export` 各半），**已删除**：
`pdf_export` 从未被写入（`PdfExport` 用 `DocumentsContract.createDocument` 直写用户选定目录），
`pic_decode` 自阅读器管线重写（f211db9 删除 `ComicPicImageState` 写入方）后同样零写入方——
解码产物早已改由 `ReaderImageDiskCache` 写进 `reader_pages`。两个分区都是界面上的恒 0 死切片，
`otherCacheMb/otherCacheBytes` 更是零消费者，故一并移除；那 10% 按 COMMON:READER 原比例（1:2）
精确拆分并入两者，比例与合计均未变。**新增分区前必须先确认存在写入方**，否则只是在给死代码加统计。

API 契约：`*DiskCacheMb/Bytes(totalMb)` 入参是**总额度**；
`componentMbToBytes(componentMb)` 入参是**已分享额的组件 MB**，禁止二次套份额。
封面链路 `CoverImageLoaderHolder.coverDiskCacheMb(cacheBudgetMb)` → `Config.componentMbToBytes`。
历史字段 `LocalSetting.coverDiskCacheMb` 仅备份透传，不再驱动 Coil。

额度收紧的执行：**各组件按自己那份技术上限自裁剪**，触发源是 `cacheBudgetMb` /
`downloadExemptFromCacheLimit` 两条设置流，不依赖下一次写入。
- READER：`ReaderImagePipeline.observeCacheBudget` → `ReaderImageDiskCache.updateMaxBytes(bytes)`
  → 立刻按 LRU 回收到上限的 7/8（`requestDiskTrim`）；显式缓存清理则走 `clearDiskCache` 代次协议。
- COMMON：`CoverImageLoaderHolder` 重建 loader 只换上限；Coil 的 DiskLruCache 在下一次
  写入（`completeEdit` 发现 `size > maxSize`）或日志重写时才会真正裁剪，属**延迟收敛**。
- `CacheCleanupViewModel` 在额度变更后用 400ms 静默窗口重扫（连续拖档位合并为一次），
  避免把裁剪前的旧占用画进饼图。

组件自裁剪只压**各组件那一份**；用户主动把额度调低到当前受控占用之下时，立即清理由用户选择：
`CacheCleanupViewModel.setBudgetMb` 先落盘（组件订阅这条流），
再按 `previousMb → newMb` 是否为「调低」且 `controlledUsedBytes > CacheBudget.totalBytes(newMb)`
挂出 `CacheCleanPrompt`（`usedBytes` / `budgetBytes` / `overflowBytes`），`CacheCleanupScreen`
弹玻璃确认框。确认走 `confirmCleanPrompt` → 与手动「清理缓存」**同一条** `runClean` 删除路径
（下载豁免语义一致）；放弃走 `dismissCleanPrompt`，额度照新值生效。判定读的是写盘后
`localSettingManager.cacheBudgetMb.value`，写盘失败（不 publish）因此不会误弹；调高、调平、
无限制一律清空上一轮询问。`CacheControlState.overBudget` 仍恒算真实占用，拒掉询问后
`CacheCleanupScreen` 把额度行染成 error 色。

冷启动自动检查：首帧后的 `PostStartupCoordinator` 单次启动任务调用 `StartupCacheCleanup.checkOnce()`。
设置不可读或无限额度时跳过；有限额度只扫描一次，按扫描结束后的最新设置比较受控已用与额度，
严格超出时清理一次，不循环追到额度以下。进程内 AtomicBoolean 去重，回前台与 Activity 重建不重扫；
扫描/清理失败写启动任务日志，不弹清理询问或阻塞首屏，也不在当前进程自动重试。
用户放弃设置页的本次立即清理，不影响下次冷启动的超额检查。

手动/自动清理共用 `cache/molecule/CacheCleanupOperations`，以同一个 Mutex 串行删除。
封面通过 `CoverImageLoaderHolder.clearDiskCache` 调 Coil 的日志协议，阅读缓存通过
`ReaderImagePipeline.clearDiskCache` 管理文件代次，不直接删除两者的活跃目录。
下载豁免开启时不触碰下载文件/任务；关闭时通过 `DownloadManager.clearDownloadedCache` 先停任务，
再清文件与任务元数据。原有 HTTP 缓存/未命名残差仍由各自组件管理，不在这条路径直接整目录删除。

额度与网格列数共用 L1 `DiscreteSettingSlider`：可点击档位文案、24dp 圆形游标、8dp 轨道、离散刻度与换档轻震。
网格保持 0=自适应、1–6=固定列数，仍在弹窗确认后保存；对应下载行统一称为「下载中心」。

统计口径：命名分区 COMMON / READER / DOWNLOAD，`ALL` 扫整个 `cacheDir`。
残差 = `ALL - 命名合计`（http_cache、cacheDir 根目录的临时文件等）计入「其他」切片与额度已用。
`CacheBudget.controlledUsedBytes` = 豁免时 `ALL - DOWNLOAD`，否则 `ALL`；界面与冷启动检查共用同一计算。
`CacheCleanupScreen` **不 import `cache.*`**，档位与明细由 VM 以 `budgetStops`/`pieSlices` 注入。

**自定义下载目录必须进统计**。用户在设置里把下载目录指到 SAF 树后，下载字节不再落在
`cacheDir` 下，只扫 `cacheDir` 会把「已下载的漫画」算成 0。
`DeviceCacheFiles` 因此持有 `ExternalDownloadSize` 端口（DI 注入 `DocumentTreeDownloadSize`，
无自定义路径时返回 0，行为与从前完全一致）。外部字节必须**同时**并入 `DOWNLOAD` 与 `ALL`：
只并入前者会让 `ALL - 命名合计` 的残差算出负数被 `coerceAtLeast(0)` 吞掉，
饼图其余分母跟着失真——`CacheFilesTest` 已钉住这条。

授权失效必须**报错而不是静默返回 0**：`cachePathSize` 把查询失败吞成 0（迁移时单本漫画读不到
不该中断整趟迁移，这个宽容要保留），所以 `DocumentTreeDownloadSize` 先用 `inspectCachePath`
探一次可访问性再取值；VM 侧 `rebuildItems`/`scheduleBudgetRescan` 统一走 `scanIntoState`
把失败原因透出。早期两处后台重扫用 `runCatching` 静默吞异常，切一下豁免开关就能把
「数不到」变成看起来正常的旧数字——这条不许回退。

**已知缺口**：`DownloadManager.clearDownloadedCache` 只删 DB 行（`deleteDownloads` 未调用
`DownloadFileRemoval`），文件删除实际靠 `files.remove(DOWNLOAD)`，而它只作用于 `cacheDir`
内的目录。**自定义路径下的下载文件目前清不掉**，统计已追索、删除尚未对齐。

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

**`reader/` 根包 15 个文件尚未归位**（共 21 个文件，仅 6 个在分层目录内），
绝大多数是 `internal` 的 Pipeline 私有协作对象：并发控制（`ReaderNetworkScheduler`、
`ReaderDynamicLimiter`、`ReaderConcurrencyPolicy`）、请求合并（`ReaderInFlightRegistry`）、
来源竞速（`ReaderRemoteFetcher`、`ReaderImageHostManager`）、
解码还原（`ReaderImageDecoder`）、预加载（`ReaderPrefetchPolicy`、`ReaderLruCache`）、
契约（`ReaderImageModels`）、UI 适配（`ComicPicImageStateReader`）。
粒度已足够小且可独立测试，**不列为迁移目标**；但它们不是分层目录的一部分，
读链路时别只看子目录。根包既被 L2 `ReaderImagePipeline` 向下使用，也向外提供给 `reader/atom`
（`ReaderImageModels` 的 `ReaderPageKey` / `ReaderDecodeProfile` / `readerCacheKey`、`ReaderLruCache`），
`reader/atom` 与 `reader/molecule` 的断言只禁止反向 import `reader/coordinator`、`ui`、`worker`，
所以这几处对根包契约的引用是**有意保留**的，不算越层。

`ReaderMetrics` 及快照入口已删除：原调试计数只有写入方，没有 UI、日志或测试消费者。
`ReaderRemoteTelemetry` 仅保留参与 CDN 排序的 TTFB 采样与主机故障反馈；
竞速、取消、许可释放和缓存代次协议保持原有行为，网络事件测试仍观察请求及取消回调。

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
13. **下载目录不做相册屏蔽**（曾经的 `cache/CacheMediaPrivacy` 已删除）：下载与迁移曾为每个
    `JM{编号}` 漫画目录写入 `.nomedia`，并在启动时补齐。**这是误判，已整体移除。**
    `.nomedia` 会让 MediaStore 剪掉整棵目录树，代价是用户在文件管理器里打开自定义缓存目录时
    看不到自己的漫画——「文件管理器仍可访问图片」这个假设并不成立（本条原有描述即为该假设）。
    若日后要恢复相册屏蔽，只能在**应用私有目录**（`cacheDir`）内做，绝不能碰用户选定目录；
    SAF 写入还必须确认文件名没有被提供器改写（如 `MyApp.provider` 会重命名 `.nomedia`）。
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
