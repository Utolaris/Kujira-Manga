# 漫画追踪与通知

## 使用

合集详情页在 JM 编码下提供「订阅」胶囊按钮，配色、圆角和文字样式沿用阅读按钮；胶囊高度 30dp，紧凑至接近作者名称的高度。
已订阅时再次点击可取消订阅。普通单本不显示该按钮：SDK 会为没有目录的漫画补出一个自身章节，
判断合集时必须排除这种占位，只有存在不同于当前漫画编号的独立章节才可追踪。

首页右上角铃铛打开通知箱，有未读通知时显示红色，默认显示全部通知。通知页点击顶部
「全部通知」（或当前分类名称）打开筛选菜单并震动，切换漫画追踪或站内通知；左上角保留返回按钮。卡片显示标题、时间、分类与未读状态；
点击展开正文并向服务器提交已读，重复点击已读通知只收起/展开正文。
右上角对勾将账号的全部通知标为已读，包括未加载的页和当前筛选之外的分类。
刷新保留当前筛选；重新进入通知页默认回到全部。

未读数量仅在冷启动检查一次，排在 `PostStartupCoordinator` 的其他任务、首页首批内容（含失败降级）及已在进行的收藏同步之后。冷启动不发起收藏同步，同步只在进入收藏页时触发。
调用轻量数量接口，不预取通知正文；失败静默，后台返回、页面切换与重组不重查。
成功标记已读后在本地递减数量，一键已读清空红色提示；账号变化立即清空旧提示，迟到响应不得回写。

## 官方协议

对照 `lab/official-jmcomic3-2.1.8/sourcemap-src/actions/memberAction.ts` 与
`components/Member/NotificationList.tsx`：

| 操作 | 请求 | 参数/响应 |
|---|---|---|
| 未读数量 | GET `notifications/unreadCount` | 无参数；`comic_follow`、`site_notice` 分类数量 |
| 通知列表 | GET `notifications` | `type=all/comic_follow/site_notice`、`page`；`list` 与 `total`，每页 20 条 |
| 标记一封已读 | POST `notifications` | `id`、`read=1`；幂等设为已读，点击已读卡片不反转为未读 |
| 追踪状态 | GET `album_sertracking` | `id`；布尔值，兼容 `0/1` |
| 订阅/取消 | POST `album_sertracking` | `id`；服务端切换状态，完成后 GET 复查 |

站内公告 `content` 是 HTML；追踪通知 `content` 是数组，包含 `comicId`、`comicTitle`、`updateDate`。
列表原样保留官方标题，追踪通知缺少标题时根据更新部数补出标题；时间使用 `date`，缺失回退 `time`。
缺少时间的旧消息明确显示「时间未提供」。`read` 兼容布尔值、数字和对应字符串，不能把字符串 `"1"`
交给 SDK 的 `Boolean.parseBoolean`，否则已读通知会一直显示未读。

SDK 1.1.8 的 `getNotifications()` 没有分页/筛选参数，因此 `EmbeddedNotificationApi` 使用官方签名
通过 SDK `executeRequest` 请求；所有操作先经过 `AuthenticatedEmbeddedClient`，共享会话恢复、
API 域名固定/切换、DoH 与登录凭据，不另建客户端。
官方未证实批量已读参数，一键已读先列出所有页，确认无重复页后再逐封提交 `read=1`，避免在遍历期间
因已读改变列表而漏项；网络失败显示错误，可刷新查看已完成部分或重试。

更新由服务端记录并生成漫画追踪通知。此功能提供 App 内的账号收件箱。

## 图文与会话

通知正文直接复用绅夜食堂 `ArticleHtmlParser` 与 `ArticleBlockContent`，保留 HTML 文字、图片和链接，
不对官方公告套用绅夜食堂广告过滤。追踪条目映射为原生文字与 `jm://comic/编号` 链接；
漫画和文章链接继续在 App 内打开。图片沿用共享 CDN 加速、缓存、DoH 与阅读器 GIF。

L1：通知页与详情按钮；L2：`NotificationViewModel` / `ComicTrackingViewModel` / `NotificationUnreadCoordinator`；
L3：`NotificationRepository`；L4：`EmbeddedNotificationApi` 与既有图文解析原子。
`AccountRequestScope` 在组合根接到 `UserManager.withBoundRemoteSession`，把读写操作绑定发起时账号与代次；
切号/登出清空显示并取消在途任务，迟到结果不能覆盖新账号或新漫画；
通知页可见时，会话恢复或切号完成会自动重取当前账号列表，离开通知页后不额外预取。

订阅 POST 是切换操作；网络超时、提交后状态复查失败时只重查 GET，不自动重放 POST，避免把刚订阅的漫画反向取消。
本地模式或未登录时不请求远端功能。

## 验证

`NotificationRepositoryTest`、`NotificationViewModelTest` 和 `EmbeddedNotificationApiTest` 覆盖协议签名、
HTML/追踪数组、数字已读标记、分页、一键已读遍历、重试与账号隔离。
`NotificationScreenTest` 提供标题/时间、点击已读、标题点按筛选和合集按钮的合成 UI 场景。
`NotificationUnreadCoordinatorTest` 与 `ColdStartFavoriteSyncTest` 覆盖冷启动只查一次、等待启动收尾（含冷启动不发收藏同步）、
账号隔离和已读时迟到结果保护；`HomeToolbarMenuTest` 覆盖首页标题点按与铃铛未读语义。
默认执行单测与 `:app:compileDebugAndroidTestKotlin`；UI 测试编译通过不等于真机运行通过。
