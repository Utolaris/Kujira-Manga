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

/**
 * Unified persistence DTO for all local settings (one encrypted JSON document). Its shape is
 * shared with backup/restore and legacy migrations, so fields are never removed casually.
 */
data class LocalSetting(
    // 开启后请求公开网络 API 获取首页推荐，不携带登录会话，可能不稳定
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
    // 已登录且今日未签到时在启动后自动签到
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
    // 网格列数：0 自适应，2-6 固定列数
    val homeGridColumns: Int = 0,
    val collectGridColumns: Int = 0,
    val downloadGridColumns: Int = 0,
    val historyGridColumns: Int = 0,
    val searchGridColumns: Int = 0,
    // 平板（大屏）布局开关。null = 尚未判定：手机侧首个非零窗口宽度写 false；
    // 平板侧由首次询问弹窗或设置开关写入。之后只由设置开关改写。
    val tabletLayoutEnabled: Boolean? = null,
    // 历史遗留：原「图片内存优化」开关。设置已移除；字段保留兼容旧备份 JSON，读取时忽略。
    val readMemoryOptEnabled: Boolean = false,
    val readDecodeConcurrency: Int = 2,
    // 已移除：homeExcludedTags（首页标签排除）。排除模板的去重并集 blockedTagList 已经全局生效
    //（首页/周刊/历史/收藏/相关推荐本地过滤 + 搜索交服务端），该字段冗余故废弃。
    // 旧存档/旧备份里仍可能有这个键，Gson 反序列化时忽略未知字段，无需迁移。
    // 封面磁盘缓存上限（MB）；历史字段，现由 cacheBudgetMb 按份额推导，读取时忽略用户旧值。
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
    // 手动开启本地模式前的说明弹窗：true = 用户点过「不再显示」。
    val localModeHelpDismissed: Boolean = false,
)

const val COLOR_PALETTE_PRESET_DEFAULT = "default"
const val COLOR_PALETTE_PRESET_OCEAN = "ocean"
const val COLOR_PALETTE_PRESET_SUNSET = "sunset"
const val COLOR_PALETTE_PRESET_FOREST = "forest"
const val COLOR_PALETTE_PRESET_LAVENDER = "lavender"
const val COLOR_PALETTE_PRESET_CUSTOM = "custom"
const val COLOR_PALETTE_PRESET_MONET = "monet"

const val READ_MODE_SCROLL = "scroll"
const val READ_MODE_PAGE = "page"
const val READ_MODE_TAP = "tap"

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
