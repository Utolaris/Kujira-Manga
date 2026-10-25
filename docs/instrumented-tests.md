# 真机插桩测试

JVM 测试覆盖不了真机行为：文件型 Room、真实文件系统 / SAF、`PdfDocument`、`WorkerParameters`。这些走 `app/src/androidTest`。

> **只有用户明确要求，才能进行真机测试。** 日常改动运行 `./gradlew :app:testDebugUnitTest`。
> 即使涉及文件型 Room、SAF、`PdfDocument`、`WorkerParameters` 或 Compose/Glass 运行时，
> 也不得自行启动真机测试；未验证的部分如实说明。详见 [AGENTS.md](../AGENTS.md)。
>
> 跑的时候请优先用统一 CLI：`./scripts/android test` / `./scripts/android test-class <FQCN>`，
> 详见 [android-cli.md](./android-cli.md)。下文是底层脚本细节。

## 怎么跑

```bash
./scripts/run-instrumented-tests.sh                      # 全量
./scripts/run-instrumented-tests.sh -p com.par9uet.jm.ui # 一个包
./scripts/run-instrumented-tests.sh -c <完整类名>         # 一个类
./scripts/run-instrumented-tests.sh -c <类名> -m <方法名>  # 一条用例
./scripts/run-instrumented-tests.sh --no-build -c ...     # 已装包、未改代码时跳过编译
./scripts/run-instrumented-tests.sh -l                    # 额外抓 logcat
./scripts/run-instrumented-tests.sh --fresh               # 卸载重装（会清登录数据）
./scripts/run-instrumented-tests.sh <序列号>               # 多台设备时指定
```

脚本会：`assembleDebug` + `assembleDebugAndroidTest` → 覆盖安装两个 APK → 准备设备 → `am instrument`。只连一台设备时可省略序列号。

默认 `install -r` **保留数据**（登录、设置、下载记录）。覆盖安装失败会直接退出；确认签名/降级问题后再用 `--fresh`。

封面解码专项可分别执行 `./scripts/android test-class com.par9uet.jm.coil.CoverBitmapDecoderTest`
与 `./scripts/android test-class com.par9uet.jm.coil.CoverDecodeBenchmarkTest`。
前者验证缩采样、透明度、EXIF、硬件位图、GIF 与不同列数间缓存复用；后者以同一 JPEG、相同目标尺寸和
硬件位图格式对比原 `BitmapFactory` 与新原生管线，覆盖 2/3/4/6 列。预热后交替顺序，各轮输出
`CoverDecodeBenchmark` 日志中的 wallMs、cpuMs、decodedBytes。该基准隔离解码，不包含网络、Compose 或滑动帧耗时；
不能直接当作掉帧率结论。真机执行需用户明确要求。

## HyperOS / MIUI：必须开「后台弹出界面」

HyperOS 4 把 instrumentation 拉起的 Activity 当成**后台弹窗**。未授权时系统会立刻把 Activity 压回 Launcher：

- 现象：app 闪一下就到桌面；Compose `waitForIdle` / `waitUntil` 一直挂着；脚本报「卡死」
- 应用本身往往没问题；单独看 logcat 也未必有明显异常
- 权限标志是 **`MIUIOP(10021)`**（设置里叫「后台弹出界面」）

脚本只做 **preflight 检查**，**不会**自动改写 appops。若为 `ignore`，会失败并打印：

```bash
adb shell appops set --user 0 kujira.manga.debug      10021 allow
adb shell appops set --user 0 kujira.manga.debug.test 10021 allow
```

检查当前状态：

```bash
adb shell appops get kujira.manga.debug | grep 10021
# 期望：MIUIOP(10021): allow
```

跑测期间请勿操作手机。脚本只做瞬时唤醒/收起通知栏，不改 stayon / DND / 白名单，也不做后台 `am start` 抢回 Activity。

### 方向锁定

插桩测试期间会**关闭方向锁定**（`accelerometer_rotation=1` + `wm user-rotation free`），避免显示管理把 Activity 固定在一个不好测的方向。

**测试结束后（含失败、卡死、Ctrl+C）会恢复测前的自动旋转状态**，原本锁定的继续锁定并尽量保留角度，原本开启自动旋转的继续开启。

## 看门狗与结果

- 默认 **180s** 没有新输出判卡死并 `force-stop`（`--stall <秒>` 可调）。当时前台窗口会写进输出。
- **不要看 `adb` 退出码**：失败、筛不到用例、runner 没起来都可能返回 0。以输出流为准：
  - `FAILURES!!!` / `STATUS_CODE: -1`（异常）/ `-2`（断言失败）/ 汇总 `Failures|Errors: [1-9]` → 失败
  - 没跑到用例、没有 `INSTRUMENTATION_CODE: -1`、数不出用例数 → 失败
- 原始输出：`build/instrumented-output.txt`；`-l` 时 logcat：`build/instrumented-logcat.txt`。
  需要底层等价命令时直接读 `scripts/run-instrumented-tests.sh`，它是唯一实现。

## 测试集

| 包 | 类 | 真机上要什么 |
| --- | --- | --- |
| backup | `BackupRestoreOperationsTest` | 备份编解码 + 下载排队 |
| cache | `DocumentCacheStorageTest` | SAF 文档 |
| cache.migration | `CacheMigrationDeviceTest` | 文件↔SAF 互迁、旧 ZIP、不可读来源不切目录；下载/迁移**不写** `.nomedia`，用户的漫画在文件管理器里可见 |
| database | `AppDatabaseMigrationTest` / `FavoriteStoreRealDatabaseTest` | Room migration；文件型 SQLite、中文搜索、账号隔离；各收藏夹共享的同步完成时间在重开数据库后保留 |
| download | `DownloadContentFilesTest` | 章节页文件读写 |
| download.export | `PdfExportDeviceTest` | 真实 `PdfDocument` + SAF；失败时用 `DocumentsContract.deleteDocument` 清不完整 PDF |
| launcher | `LauncherDisguiseInstrumentedTest` | 桌面别名 |
| network | `DohStartupRaceTest` | DoH 启动竞态 |
| storage | `SessionPersistenceTest`、`OfficialAuthSessionPersistenceTest` | 会话落盘；身份/JWT/AVS 单记录持久化与退出撤销；独立 preferences 不影响运行账号 |
| store | `FavoriteStoreSyncTest` | 收藏同步与 Room 事务（测试包仍在 `store`，主源码 `store` 已拆到领域包） |
| network | `AppHttpClientDoHDeviceTest` | 真实图片健康探测必须调用注入的 DNS，禁止绕过到系统 DNS |
| ui / ui.glass / ui.navigation / ui.viewModel | `FavoriteSyncGridTest`、`MainNavigationFlowTest`、`NavigationInteractionTest`、`SearchResultRefreshContentTest`、`PagingRefreshRetryTest`、`GlassCaptureHostSettleTest`、`RetainedMainNavigationTest`、`ReaderFavoriteMutationTest` | Compose 必须 RESUMED；Glass 静态源要能收敛；分页网格刷新失败应保留列表并可重试（组件测试，不代表真实 App 后台恢复验证） |
| ui | `DinnerGridTest` | 多列交错排布、封面比例、列数改变、整行页尾及封面点击（合成封面） |
| ui | `DinnerNavigationAndPagingTest` | Glass 重建后多列位置恢复、封面导航、触底自动加载、防重入和错误重试（合成文章） |
| ui | `DinnerArticleRenderingTest` | 原生文章段落/表格/代码显示；详情加载失败后重试恢复（合成文章组件测试，不代表真实图床或站内跳转的真机验证） |
| ui | `HomeToolbarMenuTest` | 首页标题点按分类菜单、铃铛未读状态（合成 UI） |
| ui | `NotificationScreenTest` | 通知标题/时间、点击已读、标题点按筛选、全部已读与合集订阅按钮（合成数据） |
| ui | `CommentFilterScreenTest` | 默认词/添加/移除/空列表界面、添加保存失败保留草稿（合成设置数据） |
| ui | `ComicAuthorTest` | 空白/N/A 作者显示不可点击的未知作者，有效作者保留搜索 |
| ui | `DetailLayoutReflowTest` | 横竖屏组件中间位置/尺寸、连续旋转接续、组合实例与滚动状态保留、横屏封面固定、短内容封面/介绍顶部对齐、评论高度改变不移动头部或拉开标题/作者间距、下载详情顶栏下无额外留白且横竖屏保留组合、缩窄时每帧内容不越界（合成 UI） |
| ui | `ChapterDialogViewportTest` | 短横屏长章节列表不挤掉取消/分章/合并按钮，确认仍可点击（合成 UI） |
| ui | `DetailEntryRegressionTest` | 编码/链接无来源立即显示、延迟目标保留缩略图、标题弹窗长按拖动锁定（合成 UI） |
| ui | `ComicTagSectionTest` | 单行加载骨架、同排数标签替换可见、旧缓存的空标签/角色/作品不产生空芯片 |
| ui | `GridColumnSliderDialogTest` | 网格列数复用离散额度滑条、弹窗避开顶部栏、下载中心名称、各行独立选择与确认后保存（合成设置数据） |
| ui | `CoverTransitionFlowTest` | Glass 分层入场顺序、侧滑进度/取消保持详情不动及确认后完整退场、平板双栏评论滚动保持左侧封面固定、空白区不拖动而封面可拖动回弹、反向返回/来源移除/详情封面离屏/二次触碰及侧滑隔离、阅读返回不重播与触边震动（合成 UI） |
| ui | `PressFeedbackRenderingTest` | V3 按压取消与收藏心形反馈的像素尺寸（合成 UI） |
| ui | `SubpixelGridScrollTest` | 通过 UserInput / SideEffect 两种嵌套滚动来源验证真实 LazyGrid 坐标连续性、条目不随余量重组及程序跳转后余量清除；不代表手势或默认惯性验证 |
| ui | `CoverWorkVisibilityTest` | 真实 PNG 文件经生产可见性解码器在滚动中显示；保留页/离屏可见性及淡入像素（文件源标记为 DISK，不代表 Coil 磁盘缓存命中率验证） |
| ui | `TabletModalLayoutTest` | 平板横竖窗口均为 75% 宽度、草稿保留、短窗口取消按钮可见及手机设置弹窗两侧间隙 |
| worker | `DownloadComicWorkerContractTest`、`CacheMigrationWorkerContractTest`、`LocalModeExitNotificationTest` | 真实 `WorkerParameters`；本地模式同步的常驻通知 |

普通文件系统用例在 JVM 的 `CacheFilesTest`、`LocalChapterFilesTest` 中运行，覆盖清理、阅读器缓存保护、历史布局和 ZIP；SAF、Room 和 Compose 仍保留对应 Android 测试。

测试名称只描述实际调用的路径。负向断言先等待任务进入或观察者消费完成；异常检查不能将 `AssertionError` 当作预期失败。`FavoriteSyncGridTest` 用受控协程调度器排空 Room 查询和失效通知，并以真实行变更确认分页源能收到失效。

## 调试要点

- 定位阶段只跑 `-c` / `-m`；改完代码后**不要**再加 `--no-build`。
- 堆栈用 `-l` 看 `build/instrumented-logcat.txt`。
- 测数据库残留：`adb shell ls /data/data/kujira.manga.debug/databases`。
- 整套不发起真实网络请求，飞行模式也能跑。
- PDF/SAF 用例依赖 debug 变体 Provider `kujira.manga.debug.test.cache-documents`（`app/src/debug/AndroidManifest.xml`），release 上不存在。

静态 import 边界由 JVM 的 `ArchitectureBoundaryTest` 负责；这里只测运行时行为。
