package com.par9uet.jm.storage
import com.par9uet.jm.data.models.APP_LANGUAGE_SIMPLIFIED
import com.par9uet.jm.data.models.APP_LANGUAGE_TRADITIONAL
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.BlockedTagTemplate
import com.par9uet.jm.data.models.LocalSetting
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow read views over [com.par9uet.jm.data.models.LocalSetting]. Consumers collect the relevant
 * projection instead of the whole persistence object, avoiding unrelated state updates.
 */
interface ContentPreferences {
    /**
     * 标签排除（`排除模板` 里所有模板标签的去重并集，见 [LocalSettingManager.withBlockedTagTemplates]）。
     * 客户端在 首页 / 周刊 / 历史 / 收藏 / 详情页相关推荐 本地过滤，搜索则拼进 `search_query` 交服务端排除。
     */
    val blockedTags: StateFlow<List<String>>
}

interface BlockedTagTemplatePreferences {
    val blockedTagTemplates: StateFlow<List<BlockedTagTemplate>>
}

/**
 * 内置 API 请求带的 `lang` 参数取值（[APP_LANGUAGE_SIMPLIFIED] / [APP_LANGUAGE_TRADITIONAL]）。
 *
 * 注意这是**服务端内容语言**，不是 UI 文案语言：它决定官方返回的标题 / 标签 / 分类文案。
 */
interface ContentLanguagePreferences {
    val appLanguage: StateFlow<String>
}

/** Whether the logged-in account's personalized network recommendations feed the Home page. */
interface RecommendationPreferences {
    val preferenceRecommendEnabled: StateFlow<Boolean>
}

interface ReaderPreferences {
    /** scroll | page | tap */
    val readMode: StateFlow<String>

    /** default | side */
    val readTapMode: StateFlow<String>
    val prefetchCount: StateFlow<Int>
}

interface AppExperiencePreferences {
    val onboardingCompleted: StateFlow<Boolean>
    val nsfwWarningDismissed: StateFlow<Boolean>
}

/** Explicit persistence boundary used by backup export; UI features should use narrow flows. */
interface LocalSettingSnapshotProvider {
    fun currentLocalSettingSnapshot(): LocalSetting
}

data class CacheNotificationSetting(
    val show: Boolean,
    val showName: Boolean,
)

interface CacheNotificationPreferences {
    val cacheNotification: StateFlow<CacheNotificationSetting>
}

data class GridColumnsSetting(
    val home: Int = 0,
    val collect: Int = 0,
    val download: Int = 0,
    val history: Int = 0,
    val search: Int = 0,
)

/** Small single-purpose toggles shown on the Settings home screen. */
data class MiscSettingsState(
    val clipboardAutoDetectEnabled: Boolean = false,
    val autoSignInEnabled: Boolean = true,
    /** 冷启动静默检查更新；失败不弹错、不弹窗。 */
    val autoCheckUpdateEnabled: Boolean = true,
    val gridColumns: GridColumnsSetting = GridColumnsSetting(),
    /**
     * 平板（大屏）布局是否启用。null = 尚未判定：
     * 手机侧首个非零窗口宽度会直接写入 false；
     * 平板侧由首次询问弹窗或设置开关写入（见 `ui/screens/TabletLayout.kt`）。
     */
    val tabletLayoutEnabled: Boolean? = null,
)

interface MiscSettingsPreferences {
    val misc: StateFlow<MiscSettingsState>
}

data class AppLockState(
    val enabled: Boolean = false,
    val password: String = "",
    val passwordLength: Int = 4,
    val pattern: String = "",
    val unlockMode: String = APP_LOCK_TYPE_PASSWORD,
) {
    val hasPassword: Boolean get() = password.isNotEmpty()
    val hasPattern: Boolean get() = pattern.isNotEmpty()
    val hasCredential: Boolean get() = hasPassword || hasPattern
}

interface AppSecurityPreferences {
    val appLock: StateFlow<AppLockState>
}

/**
 * Each mutator expresses one complete valid state transition, so callers do not need to compose
 * multiple writes or expose an invalid intermediate credential/mode combination.
 */
interface AppSecurityEditor {
    /** @return true only when the new security state was persisted. */
    fun setPassword(password: String, length: Int): Boolean
    fun removePassword(): Boolean
    fun setPattern(pattern: String): Boolean
    fun removePattern(): Boolean
    fun setAppLockEnabled(enabled: Boolean): Boolean
    fun disableAndClearAppLock(): Boolean
    fun selectUnlockMode(mode: String): Boolean
}

data class DohSettingsState(
    val enabled: Boolean = true,
    val autoStart: Boolean = true,
    val autoSelectFastest: Boolean = true,
    val serverId: String = "tencent",
    val customServerName: String = "",
    val customServerUrl: String = "",
    val useDeviceCertificates: Boolean = true,
    val preferIpv6: Boolean = false,
)

interface DohPreferences {
    val doh: StateFlow<DohSettingsState>
}

interface DohPreferencesEditor {
    /** @return true only when the new preference was persisted. */
    fun persistEnabled(enabled: Boolean): Boolean
    fun persistAutoStart(enabled: Boolean): Boolean
    fun persistAutoSelectFastest(enabled: Boolean): Boolean
    fun persistServer(serverId: String): Boolean
    fun persistCustomServer(name: String, url: String): Boolean
    fun persistUseDeviceCertificates(enabled: Boolean): Boolean
    fun persistPreferIpv6(enabled: Boolean): Boolean
}

data class ColorPaletteState(
    val presetId: String,
    val customPrimary: String?,
    val customSecondary: String?,
    val customTertiary: String?,
    val customError: String?,
) {
    val hasCustomOverride: Boolean
        get() = customPrimary != null || customSecondary != null ||
            customTertiary != null || customError != null
}

interface AppearancePreferences {
    /** auto | light | dark */
    val theme: StateFlow<String>
    val colorPalette: StateFlow<ColorPaletteState>
    /** Currently selected launcher alias id ([LauncherDisguise]). */
    val launcherDisguiseId: StateFlow<String>
    val editor: AppearanceEditor
}

/** Base URL for the optional Retrofit recommendation service; Embedded API routing is independent. */
interface ApiEndpointPreference {
    val apiEndpoint: StateFlow<String>
}

/** Narrow read view of server-delivered runtime configuration. */
interface RemoteConfigPreferences {
    /** Preferred image CDN host from the server; blank until the first successful refresh. */
    val remoteImageHost: StateFlow<String>
}
interface AppearanceEditor {
    /** Atomic preset switch: clears custom color overrides in the same transition. */
    fun selectColorPreset(presetId: String)

    /** Atomic custom-color confirm: switches the palette to custom in the same transition. */
    fun applyCustomColors(primary: String?, secondary: String?, tertiary: String?, error: String?)
}
