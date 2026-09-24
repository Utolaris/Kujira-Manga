package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.data.models.AVAILABLE_APIS
import com.par9uet.jm.data.models.AVAILABLE_APP_LANGUAGES
import com.par9uet.jm.data.models.AVAILABLE_THEMES
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.storage.ApiEndpointPreference
import com.par9uet.jm.storage.AppSecurityPreferences
import com.par9uet.jm.storage.AppearancePreferences
import com.par9uet.jm.storage.CacheNotificationPreferences
import com.par9uet.jm.storage.CacheNotificationSetting
import com.par9uet.jm.storage.ColorPaletteState
import com.par9uet.jm.storage.ContentLanguagePreferences
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohSettingsState
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.MiscSettingsState
import com.par9uet.jm.storage.ReaderPreferences
import com.par9uet.jm.storage.RecommendationPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Settings home screen renders, composed from narrow preference flows. */
data class SettingsUiState(
    val theme: String = AVAILABLE_THEMES.first(),
    val colorPalette: ColorPaletteState = ColorPaletteState("default", null, null, null, null),
    val launcherDisguiseId: String = LauncherDisguise.Default.id,
    val apiEndpoint: String = AVAILABLE_APIS.first(),
    val appLanguage: String = AVAILABLE_APP_LANGUAGES.first(),
    val recommendationEnabled: Boolean = true,
    val clipboardAutoDetectEnabled: Boolean = false,
    val autoSignInEnabled: Boolean = true,
    val prefetchCount: Int = 3,
    val readMode: String = "scroll",
    val notification: CacheNotificationSetting = CacheNotificationSetting(show = true, showName = true),
    val appLockEnabled: Boolean = false,
    val appLockHasPassword: Boolean = false,
    val appLockHasPattern: Boolean = false,
    val doh: DohSettingsState = DohSettingsState(),
    val gridColumns: GridColumnsSnapshot = GridColumnsSnapshot(),
    /** null = 尚未判定（首个非零窗口宽度决定默认值）。 */
    val tabletLayoutEnabled: Boolean? = null,
    val cacheBudgetMb: Int = 1024,
) {
    /** Human-readable one-line summary of the current app lock state. */
    fun appLockSummaryText(): String {
        if (!appLockEnabled) return "未启用"
        val methods = buildList {
            if (appLockHasPassword) add("密码")
            if (appLockHasPattern) add("图案")
        }
        return if (methods.isEmpty()) "已启用" else "已启用 - " + methods.joinToString("+")
    }
}

data class GridColumnsSnapshot(
    val home: Int = 0,
    val collect: Int = 0,
    val download: Int = 0,
    val history: Int = 0,
    val search: Int = 0,
)

private data class AppearanceSnapshot(
    val theme: String,
    val colorPalette: ColorPaletteState,
    val launcherDisguiseId: String,
    val recommendationEnabled: Boolean,
)

private data class ReaderSnapshot(
    val prefetchCount: Int,
    val readMode: String,
)


private data class CombinedMiscSnapshot(
    val apiEndpoint: String,
    val appLanguage: String,
    val appLock: com.par9uet.jm.storage.AppLockState,
    val doh: DohSettingsState,
    val misc: com.par9uet.jm.storage.MiscSettingsState,
)
/**
 * Control owner for the Settings home screen only; sub-screens keep their own narrow facades
 * (AppSecurityEditor / DohManager / AppearanceEditor). The UI collects [uiState] and sends
 * actions; catalog validation lives here, compound invariants live in LocalSettingManager.
 */
class SettingsViewModel(
    recommendationPreferences: RecommendationPreferences,
    readerPreferences: ReaderPreferences,
    cacheNotificationPreferences: CacheNotificationPreferences,
    private val appearancePreferences: AppearancePreferences,
    securityPreferences: AppSecurityPreferences,
    dohPreferences: DohPreferences,
    apiEndpointPreference: ApiEndpointPreference,
    contentLanguagePreferences: ContentLanguagePreferences,
    miscSettings: com.par9uet.jm.storage.MiscSettingsPreferences,
    private val localSettingManager: LocalSettingManager,
    private val favoriteSyncRequester: FavoriteSyncRequester,
    private val favoriteForceAlign: com.par9uet.jm.core.model.FavoriteForceAlign,
) : ViewModel() {

    /** Narrow prefs facades for settings-adjacent screens (palette / templates / grids). */
    val misc: StateFlow<MiscSettingsState> = localSettingManager.misc
    val blockedTagTemplates = localSettingManager.blockedTagTemplates
    val colorPalette: StateFlow<ColorPaletteState> = appearancePreferences.colorPalette
    val localModeHelpDismissed: StateFlow<Boolean> = localSettingManager.localModeHelpDismissed

    fun dismissLocalModeHelp() = localSettingManager.dismissLocalModeHelp()

    fun saveBlockedTagTemplate(index: Int?, name: String, tags: List<String>) =
        localSettingManager.saveBlockedTagTemplate(index, name, tags)

    fun removeBlockedTagTemplate(index: Int) =
        localSettingManager.removeBlockedTagTemplate(index)

    fun selectColorPreset(presetId: String) =
        appearancePreferences.editor.selectColorPreset(presetId)

    fun applyCustomColors(
        primary: String?,
        secondary: String?,
        tertiary: String?,
        error: String?,
    ) = appearancePreferences.editor.applyCustomColors(primary, secondary, tertiary, error)


    // Hierarchical combine keeps each combine within its 5-flow typed overload.
    private val appearanceState = combine(
        appearancePreferences.theme,
        appearancePreferences.colorPalette,
        appearancePreferences.launcherDisguiseId,
        recommendationPreferences.preferenceRecommendEnabled,
    ) { theme, palette, disguiseId, recommend ->
        AppearanceSnapshot(theme, palette, disguiseId, recommend)
    }

    private val readerState = combine(
        readerPreferences.prefetchCount,
        readerPreferences.readMode,
    ) { prefetch, mode -> ReaderSnapshot(prefetch, mode) }

    private val miscState = combine(
        apiEndpointPreference.apiEndpoint,
        contentLanguagePreferences.appLanguage,
        securityPreferences.appLock,
        dohPreferences.doh,
        miscSettings.misc,
    ) { api, language, appLock, doh, misc ->
        CombinedMiscSnapshot(api, language, appLock, doh, misc)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        appearanceState,
        readerState,
        miscState,
        cacheNotificationPreferences.cacheNotification,
        localSettingManager.cacheBudgetMb,
    ) { appearance, reader, misc, notification, cacheBudgetMb ->
        SettingsUiState(
            theme = appearance.theme,
            colorPalette = appearance.colorPalette,
            launcherDisguiseId = appearance.launcherDisguiseId,
            recommendationEnabled = appearance.recommendationEnabled,
            apiEndpoint = misc.apiEndpoint,
            appLanguage = misc.appLanguage,
            prefetchCount = reader.prefetchCount,
            readMode = reader.readMode,
            notification = notification,
            appLockEnabled = misc.appLock.enabled,
            appLockHasPassword = misc.appLock.hasPassword,
            appLockHasPattern = misc.appLock.hasPattern,
            doh = misc.doh,
            clipboardAutoDetectEnabled = misc.misc.clipboardAutoDetectEnabled,
            autoSignInEnabled = misc.misc.autoSignInEnabled,
            gridColumns = GridColumnsSnapshot(
                home = misc.misc.gridColumns.home,
                collect = misc.misc.gridColumns.collect,
                download = misc.misc.gridColumns.download,
                history = misc.misc.gridColumns.history,
                search = misc.misc.gridColumns.search,
            ),
            tabletLayoutEnabled = misc.misc.tabletLayoutEnabled,
            cacheBudgetMb = cacheBudgetMb,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val favoriteSyncState: StateFlow<FavoriteSyncUiState> = favoriteSyncRequester.state

    /**
     * 强制让收藏夹与远端对齐（丢弃未同步本地变更并关闭本地模式）。
     * 先弹确认框，由设置页在确认后调用。
     */
    fun requestFavoriteForceRefresh() = viewModelScope.launch {
        if (favoriteSyncRequester.state.value.isSyncing) return@launch
        favoriteForceAlign.forceAlignFavoritesWithRemote()
    }

    // ---- simple settings intents ----

    fun setPreferenceRecommendEnabled(enabled: Boolean) =
        localSettingManager.setPreferenceRecommendEnabled(enabled)

    fun setAutoSignInEnabled(enabled: Boolean) =
        localSettingManager.updateAutoSignInEnabled(enabled)

    /** @return true only when the preference write confirmed; UI can then toast on false. */
    fun setClipboardAutoDetectEnabled(enabled: Boolean): Boolean =
        localSettingManager.updateClipboardAutoDetectEnabled(enabled)

    fun setPrefetchCount(count: Int) = localSettingManager.setPrefetchCount(count)

    fun setReadMode(mode: String) = localSettingManager.setReadMode(mode)

    fun selectApi(url: String) {
        require(url in AVAILABLE_API_SET) { "未知 API 节点" }
        localSettingManager.setApiEndpoint(url)
    }

    fun selectTheme(theme: String) {
        require(theme in AVAILABLE_THEME_SET) { "未知主题" }
        localSettingManager.applyTheme(theme)
    }

    fun selectLauncherDisguise(id: String) = localSettingManager.updateLauncherDisguise(id)

    /** 服务端内容语言（内置 API 的 `lang`）；非法取值由 LocalSettingManager 收口。 */
    fun selectAppLanguage(language: String) = localSettingManager.updateAppLanguage(language)

    /** One notification-dialog intent maps to the derived show/showName pair. */
    fun applyNotificationSetting(show: Boolean, showName: Boolean) =
        localSettingManager.applyNotificationSetting(show, showName)

    /** One grid-dialog confirm updates all five page columns atomically. */
    fun applyGridColumns(home: Int, collect: Int, download: Int, history: Int, search: Int) =
        localSettingManager.applyGridColumns(home, collect, download, history, search)

    /** 平板布局开关；写入后不再随窗口宽度自动变化。 */
    fun setCacheBudgetMb(mb: Int) = localSettingManager.setCacheBudgetMb(mb)

    fun setTabletLayoutEnabled(enabled: Boolean) =
        localSettingManager.setTabletLayoutEnabled(enabled)

    companion object {
        private val AVAILABLE_API_SET = AVAILABLE_APIS.toSet()
        private val AVAILABLE_THEME_SET = AVAILABLE_THEMES.toSet()
    }
}
