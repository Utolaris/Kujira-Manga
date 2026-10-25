package com.par9uet.jm.data.models

const val APP_LOCK_TYPE_PASSWORD = "password"
const val APP_LOCK_TYPE_PATTERN = "pattern"
const val APP_LOCK_UNLOCK_MODE_PASSWORD = "password"
const val APP_LOCK_UNLOCK_MODE_PATTERN = "pattern"
const val APP_LOCK_UNLOCK_MODE_BOTH = "both"

// Supported API endpoints are a static application catalog, not user preferences; only the
// selected value is persisted in LocalSetting.api.
val AVAILABLE_APIS = listOf(
    "https://www.cdnhth.club",
    "https://www.cdnmhwscc.vip",
    "https://www.jmapiproxyxxx.vip",
    "https://www.cdnxxx-proxy.xyz",
    "https://www.jmeadpoolcdn.life",
)

// Supported themes; only the selected value is persisted in LocalSetting.theme.
val AVAILABLE_THEMES = listOf("auto", "light", "dark")

data class BlockedTagTemplate(
    val name: String = "",
    val tagList: List<String> = listOf(),
)

val DEFAULT_COMMENT_FILTER_KEYWORDS = listOf("扣群", "加群", "VIP", "可约", "一对一")

/**
 * Unified persistence DTO for all local settings (one encrypted JSON document). Its shape is
 * shared with backup/restore and legacy migrations, so fields are never removed casually.
 */
data class LocalSetting(
    // 开启后请求公开网络 API 获取首页推荐，不携带登录会话，可能不稳定
    // Legacy backup field; LocalSettingManager normalizes this feature to enabled.
    val preferenceRecommendEnabled: Boolean = true,
    // 选中的 API 节点（候选集合见 [AVAILABLE_APIS]）
    val api: String = AVAILABLE_APIS.first(),
    // auto | light | dark（候选集合见 [AVAILABLE_THEMES]）
    val theme: String = "auto",
    // 内置 API 请求的 lang 参数：CN = 简体，TW = 繁體（候选集合见 [AVAILABLE_APP_LANGUAGES]）
    val appLanguage: String = DEFAULT_APP_LANGUAGE,
    // 阅读页预先加载的图片张数
    val prefetchCount: Int = 3,
    // scroll | page | tap
    val readMode: String = READ_MODE_SCROLL,
    // default | side
    val readTapMode: String = "default",
    val launcherDisguise: String = "default",
    val showComicCacheNotification: Boolean = true,
    // 仅在 showComicCacheNotification 为 true 时有意义
    val showComicCacheNotificationName: Boolean = true,
    val blockedTagList: List<String> = listOf(),
    val blockedTagTemplateList: List<BlockedTagTemplate> = listOf(),
    // null / 旧设置缺字段使用默认词；显式空列表表示用户已清空，不能重新填入默认词。
    val blockedCommentKeywordList: List<String>? = DEFAULT_COMMENT_FILTER_KEYWORDS,
    val appLockEnabled: Boolean = false,
    // 空字符串表示未设置密码
    val appLockPassword: String = "",
    // 密码长度 4-8 位
    val appLockPasswordLength: Int = 4,
    // 空字符串表示未设置图案（点序号拼接，例如 "01246"）
    val appLockPattern: String = "",
    // password | pattern | both；必须与实际存在的凭据一致
    val appLockUnlockMode: String = APP_LOCK_UNLOCK_MODE_PASSWORD,
    val nsfwWarningDismissed: Boolean = false,
    val onboardingCompleted: Boolean = false,
    // 检测到剪贴板包含漫画编码时自动弹出跳转提示
    val clipboardAutoDetectEnabled: Boolean = false,
    // 已登录且今日未签到时在启动后自动签到；旧备份中的关闭值会归一为开启。
    val autoSignInEnabled: Boolean = true,
    // 冷启动后静默检查 GitHub Release；失败不弹错。仅进程首次启动触发，从后台唤起不检查。
    val autoCheckUpdateEnabled: Boolean = true,
    // "default" 表示主题默认配色，其余为内置预设 ID 或 custom
    val colorPalettePreset: String = COLOR_PALETTE_PRESET_DEFAULT,
    // 自定义四色（ARGB hex，如 "#FF4F5F7F"）；null 表示跟随预设
    val customColorPrimary: String? = null,
    val customColorSecondary: String? = null,
    val customColorTertiary: String? = null,
    val customColorError: String? = null,
    // DoH 默认启用；dohAutoStart 控制进程启动时自动激活
    val dohEnabled: Boolean = true,
    val dohAutoStart: Boolean = true,
    // 启动后在后台对内置线路测速并切到延迟最低的一条；关掉后可手动固定线路
    val dohAutoSelectFastest: Boolean = true,
    val dohServerId: String = "tencent",
    val dohCustomServerName: String = "",
    val dohCustomServerUrl: String = "",
    // DoH TLS 校验是否信任用户安装的设备证书
    val dohUseDeviceCertificates: Boolean = true,
    val dohPreferIpv6: Boolean = false,
    // 网格列数：0 自适应，1-6 固定列数
    val homeGridColumns: Int = 0,
    val collectGridColumns: Int = 0,
    val downloadGridColumns: Int = 0,
    val historyGridColumns: Int = 0,
    val searchGridColumns: Int = 0,
    // 平板（大屏）布局开关。null = 尚未判定：手机侧首个非零窗口宽度写 false；
    // 平板侧由首次询问弹窗或设置开关写入。之后只由设置开关改写。
    val tabletLayoutEnabled: Boolean? = null,
    // 隐藏主导航按钮（首页/收藏/设置）。null = 未显式设置：跟随平板布局开关
    // （平板默认隐藏，手机默认显示）。显式设置后只由设置开关改写。
    val hideNavigationButtons: Boolean? = null,
    // 封面磁盘缓存上限（MB）；历史字段，仅随备份透传，现由 cacheBudgetMb 按份额推导。
    val coverDiskCacheMb: Int = 256,
    // 缓存控制：总预算（MB）。各命名空间按 CacheBudget 推荐份额分配。
    val cacheBudgetMb: Int = 1024,
    // true = 已下载漫画不受缓存总配额限制（默认）
    val downloadExemptFromCacheLimit: Boolean = true,
    // 每个已登录账号独立保留本地模式，切换账号时不会混用收藏和历史。
    // Gson 读取旧设置时缺字段会给 null，使用方统一按空列表处理。
    val localModeAccountIds: List<Int>? = emptyList(),
    // 本地模式开启时间：用于避免白天手动开启后立刻自动切回；旧设置缺字段按可尝试恢复处理。
    val localModeEnteredAtByAccount: Map<Int, Long>? = emptyMap(),
    // 夜间 401 弹窗去重（本地日期 yyyy-MM-dd）。Gson 缺字段会写 null，读取方必须 orEmpty。
    val nightLocalModePromptDate: String? = null,
    // 仅兼容旧设置与备份；每次开启本地收藏模式都确认，不再读取此标记。
    @Deprecated("本地收藏模式每次开启都需要确认")
    val localModeHelpDismissed: Boolean = false,
    // 全局高斯模糊水平 0-100；50 = 平衡（默认）。旧 JSON 缺字段时由存储层回填 50。
    val glassBlurLevel: Int = 50,
)

const val COLOR_PALETTE_PRESET_DEFAULT = "default"
const val COLOR_PALETTE_PRESET_CUSTOM = "custom"
const val COLOR_PALETTE_PRESET_MONET = "monet"

const val READ_MODE_SCROLL = "scroll"

/**
 * 内置 API 请求带的 `lang` 参数，对齐官方 app 的 `localStorage.lang`。
 *
 * 官方只认这两个值且**都是大写**（`HttpUtil.fetchGet` 无条件补 `lang`），语义上是繁體 / 简体。
 * 该参数**影响服务端返回的内容**（标题 / 标签 / 分类文案），不只是 UI 文案 —— 官方切换语言时
 * 会清空主列表并重拉设置，正是因为这个。
 */
const val APP_LANGUAGE_SIMPLIFIED = "CN"
const val APP_LANGUAGE_TRADITIONAL = "TW"

val AVAILABLE_APP_LANGUAGES = listOf(APP_LANGUAGE_SIMPLIFIED, APP_LANGUAGE_TRADITIONAL)

/** 与官方相反：官方缺省繁體，本项目缺省简体。 */
const val DEFAULT_APP_LANGUAGE = APP_LANGUAGE_SIMPLIFIED

/**
 * 把任意读取值收口到 [AVAILABLE_APP_LANGUAGES]。
 *
 * `LocalSetting` 由 Gson 反序列化，字段缺失时会被留成 null（Kotlin 默认值不生效），
 * 所以这里必须吃 `String?` 而不是 `String`。
 */
fun coerceAppLanguage(value: String?): String =
    value?.takeIf { it in AVAILABLE_APP_LANGUAGES } ?: DEFAULT_APP_LANGUAGE
