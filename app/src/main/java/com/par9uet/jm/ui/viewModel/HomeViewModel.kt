package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.storage.RecommendationPreferences
import com.par9uet.jm.storage.ContentLanguagePreferences
import com.par9uet.jm.startup.StartupHomeLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** The Home landing page is always the server/catalog's current second page. */
internal fun <T> swapFirstTwoHomePages(input: List<T>): List<T> {
    if (input.size < 2) return input
    return buildList(input.size) {
        add(input[1])
        add(input[0])
        addAll(input.drop(2))
    }
}

class HomeViewModel(
    private val comicRepository: ComicRepository,
    private val recommendationPreferences: RecommendationPreferences,
    private val contentPreferences: com.par9uet.jm.storage.ContentPreferences,
    private val miscSettingsPreferences: com.par9uet.jm.storage.MiscSettingsPreferences,
    unreadStatus: com.par9uet.jm.notification.model.NotificationUnreadStatus,
    contentLanguagePreferences: ContentLanguagePreferences,
    private val startupHome: StartupHomeLoad = StartupHomeLoad(),
) : ViewModel() {
    /** HomeScreen collects these instead of service-locating LocalSettingManager. */
    val preferenceRecommendEnabled = recommendationPreferences.preferenceRecommendEnabled
    val blockedTags = contentPreferences.blockedTags
    val misc = miscSettingsPreferences.misc
    val hasUnreadNotifications = unreadStatus.hasUnread
    val appLanguage = contentLanguagePreferences.appLanguage

    /**
     * 首页分类描述：id 供仓库加载，title 为展示名。
     * content 是该栏目的完整列表，来自 /promote；首次点击前不进正文，避免首屏一次灌入全部栏目。
     */
    data class HomeCategoryInfo(
        val id: String,
        val title: String,
        val content: List<Comic> = emptyList(),
    )

    data class HomeCategoryLoadState(
        val content: List<Comic> = emptyList(),
        val isLoading: Boolean = false,
        val isError: Boolean = false,
        val errorMsg: String? = null,
    )

    data class HomeUIState(
        val categories: List<HomeCategoryInfo> = emptyList(),
        val selectedCategoryId: String? = null,
        val states: Map<String, HomeCategoryLoadState> = emptyMap(),
    )

    private val _homeState = MutableStateFlow(HomeUIState())
    val homeState = _homeState.asStateFlow()

    private data class HomeRequestToken(
        val topologyGeneration: Long,
        val requestGeneration: Long,
        val key: String,
    )

    private var homeTopologyGeneration = 0L
    private var homeRequestGeneration = 0L
    private var lastPreferenceRecommendEnabled: Boolean? = null
    private var lastAppLanguage = appLanguage.value
    private val homeCategoryCache =
        mutableMapOf<String, List<Comic>>()
    private var networkHomeCache: List<HomeComicSwiperItem>? = null
    private val activeCategoryLoads = mutableMapOf<String, HomeRequestToken>()
    private var homeActive = false
    private var promoteRetryNeeded = false
    private var promoteRetryAttempt = 0
    private var promoteRetryJob: Job? = null

    companion object {
        const val CATEGORY_LATEST = "builtin_latest"
        private const val NETWORK_CATEGORY_PREFIX = "net_"
        private const val PROMOTE_LOAD_KEY = "promote_sections"
        private val PROMOTE_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
        val EMBEDDED_CATEGORIES = swapFirstTwoHomePages(
            listOf(
                HomeCategoryInfo(CATEGORY_LATEST, "最新上架"),
                HomeCategoryInfo("builtin_week_hot", "本周热门"),
                HomeCategoryInfo("builtin_month_hot", "本月热门"),
                HomeCategoryInfo("builtin_most_liked", "最多喜欢"),
                HomeCategoryInfo("builtin_random", "随机推荐"),
                HomeCategoryInfo("builtin_doujin", "同人"),
                HomeCategoryInfo("builtin_single", "单本"),
                HomeCategoryInfo("builtin_short", "短篇"),
                HomeCategoryInfo("builtin_korean", "韩漫"),
                HomeCategoryInfo("builtin_american", "美漫"),
                HomeCategoryInfo("builtin_cosplay", "Cosplay"),
                HomeCategoryInfo("builtin_3d", "3D"),
                HomeCategoryInfo("builtin_most_images", "图片最多"),
            )
        )
    }

    /**
     * Rebuild Home from Embedded categories plus the optional network recommendation feed.
     * 只有选中栏目会立即加载；推荐栏目与内置分类都在用户点击后才取完整列表。
     */
    fun refreshHome() {
        val recommendEnabled = recommendationPreferences.preferenceRecommendEnabled.value
        val languageChanged = appLanguage.value != lastAppLanguage
        if (languageChanged) {
            homeCategoryCache.clear()
            networkHomeCache = null
        }
        val topologyChanged = recommendEnabled != lastPreferenceRecommendEnabled || languageChanged
        if (topologyChanged) {
            promoteRetryJob?.cancel()
            promoteRetryJob = null
            promoteRetryNeeded = false
            promoteRetryAttempt = 0
            // 分类结构变化时作废所有旧 token。阻塞式 JM 请求即使稍后返回，也不能写回新结构。
            homeTopologyGeneration++
            activeCategoryLoads.clear()
            _homeState.value = HomeUIState()
        }
        lastPreferenceRecommendEnabled = recommendEnabled
        lastAppLanguage = appLanguage.value

        if (recommendEnabled) {
            val cached = networkHomeCache
            if (cached != null) {
                applyPromoteHome(cached)
            } else {
                loadPromoteSections()
            }
        } else {
            applyEmbeddedHome()
        }
        // 推荐开启时 /promote 只给栏目名与首屏列表；内置分类与未点开的推荐栏目按需加载。
        _homeState.value.selectedCategoryId?.let { selected ->
            if (_homeState.value.categories.any { it.id == selected }) ensureCategoryLoaded(selected)
        }
    }

    /** L1 reports visible Home + started lifecycle; retry policy stays in this coordinator. */
    fun setHomeActive(active: Boolean) {
        if (homeActive == active) return
        homeActive = active
        if (!active) {
            promoteRetryJob?.cancel()
            promoteRetryJob = null
        } else if (shouldRetryPromote()) {
            promoteRetryAttempt = 0
            loadPromoteSections()
        }
    }

    private fun shouldRetryPromote(): Boolean =
        homeActive && recommendationPreferences.preferenceRecommendEnabled.value &&
            lastPreferenceRecommendEnabled == true && promoteRetryNeeded && networkHomeCache == null

    private fun schedulePromoteRetry() {
        if (!shouldRetryPromote() || promoteRetryJob?.isActive == true) return
        val retryDelay = PROMOTE_RETRY_DELAYS_MS[promoteRetryAttempt]
        promoteRetryAttempt = (promoteRetryAttempt + 1).coerceAtMost(PROMOTE_RETRY_DELAYS_MS.lastIndex)
        promoteRetryJob = viewModelScope.launch {
            delay(retryDelay)
            promoteRetryJob = null
            if (shouldRetryPromote()) loadPromoteSections()
        }
    }

    fun selectHomeCategory(categoryId: String) {
        val current = _homeState.value
        if (current.categories.none { it.id == categoryId }) return
        if (current.selectedCategoryId == categoryId) {
            // A → B → A 或旧请求被作废后，即使 UI 已选中 A，也要保证 A 有缓存或有效请求。
            ensureCategoryLoaded(categoryId)
            return
        }
        _homeState.update { it.copy(selectedCategoryId = categoryId) }
        ensureCategoryLoaded(categoryId)
    }

    /** Explicit retry for the currently selected category after a load failure. */
    fun refreshSelectedHomeCategory() {
        val selected = _homeState.value.selectedCategoryId ?: return
        ensureCategoryLoaded(selected, force = true)
    }

    private fun ensureCategoryLoaded(categoryId: String, force: Boolean = false) {
        val state = _homeState.value.states[categoryId]
        val active = activeCategoryLoads[categoryId]
        if (!force && active?.topologyGeneration == homeTopologyGeneration) return
        if (!force && hydrateCategory(categoryId)) return

        if (isPromoteCategory(categoryId)) {
            // 推荐栏目的内容随 /promote 下发；点击未加载的栏目就是按需补齐这份目录。
            loadPromoteSections(
                force = force,
                loadingCategoryId = categoryId,
            )
            return
        }

        val token = newHomeRequestToken(categoryId)
        activeCategoryLoads[categoryId] = token
        updateCategoryState(
            categoryId,
            (state ?: HomeCategoryLoadState()).copy(
                isLoading = true,
                isError = false,
                errorMsg = null,
            )
        )
        viewModelScope.launch {
            try {
                if (!isCurrentHomeRequest(token, requireCategory = true)) return@launch
                val result = comicRepository.getEmbeddedHomeCategory(categoryId)
                ensureActive()
                if (!isCurrentHomeRequest(token, requireCategory = true)) return@launch

                when (result) {
                    is NetWorkResult.Error -> {
                        val current = _homeState.value.states[categoryId] ?: HomeCategoryLoadState()
                        updateCategoryState(
                            categoryId,
                            current.copy(
                                isLoading = false,
                                isError = true,
                                errorMsg = result.message,
                            )
                        )
                    }

                    is NetWorkResult.Success -> {
                        homeCategoryCache[categoryId] = result.data
                        updateCategoryState(categoryId, HomeCategoryLoadState(content = result.data))
                    }
                }
            } finally {
                if (activeCategoryLoads[categoryId] == token) {
                    activeCategoryLoads.remove(categoryId)
                }
            }
        }.also(startupHome::track)
    }

    /**
     * 拉取 /promote 栏目目录。首批内容只随响应填充尚未显示的栏目；之后切换栏目一律走
     * [hydrateCategory]，不重新缓存整份推荐。
     */
    private fun loadPromoteSections(
        force: Boolean = false,
        loadingCategoryId: String? = null,
    ) {
        val active = activeCategoryLoads[PROMOTE_LOAD_KEY]
        if (!force && active?.topologyGeneration == homeTopologyGeneration) return
        promoteRetryJob?.cancel()
        promoteRetryJob = null

        val token = newHomeRequestToken(PROMOTE_LOAD_KEY)
        activeCategoryLoads[PROMOTE_LOAD_KEY] = token
        // 点击未加载的推荐栏目时可见分类还没进 categories，只能先标记它的加载状态。
        loadingCategoryId?.let { categoryId ->
            val current = _homeState.value.states[categoryId] ?: HomeCategoryLoadState()
            updateCategoryState(
                categoryId,
                current.copy(isLoading = true, isError = false, errorMsg = null),
            )
        }
        viewModelScope.launch {
            try {
                if (!isCurrentHomeRequest(token)) return@launch
                val result = comicRepository.getNetworkHomePage()
                ensureActive()
                if (!isCurrentHomeRequest(token)) return@launch
                when (result) {
                    is NetWorkResult.Success -> {
                        // 空响应不算可用推荐：不能被缓存成整个进程的成功结果，否则再也没有补齐栏目的机会，
                        // 只有拿到非空栏目目录才算这次推荐请求成功。
                        val hasContent = result.data.isNotEmpty()
                        promoteRetryNeeded = !hasContent
                        if (hasContent) {
                            networkHomeCache = result.data
                            promoteRetryAttempt = 0
                            applyPromoteHome(result.data)
                        } else {
                            // 已有可用推荐时，暂时空响应也不能把栏目抹掉，只重试；
                            // 没有可用缓存时退回内置分类，并立即补齐选中栏目。
                            val cached = networkHomeCache
                            if (cached != null) {
                                applyPromoteHome(cached)
                            } else {
                                applyEmbeddedHome()
                            }
                        }
                    }

                    is NetWorkResult.Error -> {
                        promoteRetryNeeded = true
                        when {
                            // 点击栏目触发的加载失败只落在该栏目上，不换掉首页。
                            loadingCategoryId != null -> {
                                val current = _homeState.value.states[loadingCategoryId]
                                    ?: HomeCategoryLoadState()
                                updateCategoryState(
                                    loadingCategoryId,
                                    current.copy(
                                        isLoading = false,
                                        isError = true,
                                        errorMsg = result.message,
                                    ),
                                )
                            }

                            else -> applyEmbeddedHome()
                        }
                    }
                }
            } finally {
                if (activeCategoryLoads[PROMOTE_LOAD_KEY] == token) {
                    activeCategoryLoads.remove(PROMOTE_LOAD_KEY)
                    schedulePromoteRetry()
                }
            }
        }.also(startupHome::track)
    }

    /**
     * /promote 只决定栏目顺序与标题；每个栏目的完整列表留在 [HomeCategoryInfo.content]，
     * 只有当前选中栏目随响应进入正文状态，切换栏目时再取。
     * 推荐栏目的内容都来自这一份响应，刷新时据此保留用户已加载的栏目。
     */
    private fun applyPromoteHome(categories: List<HomeComicSwiperItem>) {
        val promoteSections = categories.filter { it.list.isNotEmpty() }
        if (promoteSections.isEmpty()) {
            applyEmbeddedHome()
            return
        }
        // 前两项换位只调整入口顺序，不改变各栏目自身内容。
        val networkInfos = swapFirstTwoHomePages(promoteSections).map { item ->
            HomeCategoryInfo(
                id = networkCategoryId(item.id),
                title = item.title,
                content = item.list,
            )
        }
        val allInfos = networkInfos + EMBEDDED_CATEGORIES
        val validIds = allInfos.mapTo(mutableSetOf()) { it.id }
        val newStates = _homeState.value.states
            .filterKeys { it in validIds }
            .toMutableMap()
        EMBEDDED_CATEGORIES.forEach { info ->
            homeCategoryCache[info.id]?.let { content ->
                newStates[info.id] = HomeCategoryLoadState(content = content)
            }
        }
        val selected = _homeState.value.selectedCategoryId
            ?.takeIf { it in validIds }
            ?: networkInfos.first().id
        // 只有当前栏目随本次响应直接显示；其余栏目等点击后再进 states。
        // 已有内容（含请求中的加载状态/最近一次错误）优先保留，只有真正没有内容时才用目录填充。
        if (allInfos.any { it.id.startsWith(NETWORK_CATEGORY_PREFIX) }) {
            allInfos.firstOrNull { it.id == selected && it.content.isNotEmpty() }
                ?.let { info ->
                    val existing = newStates[info.id]
                    newStates[info.id] = existing?.copy(content = info.content)
                        ?: HomeCategoryLoadState(content = info.content)
                }
        }
        _homeState.value = HomeUIState(
            categories = allInfos,
            selectedCategoryId = selected,
            states = newStates,
        )
    }

    private fun applyEmbeddedHome() {
        val validIds = EMBEDDED_CATEGORIES.mapTo(mutableSetOf()) { it.id }
        val states = _homeState.value.states
            .filterKeys { it in validIds }
            .toMutableMap()
        EMBEDDED_CATEGORIES.forEach { info ->
            homeCategoryCache[info.id]?.let { content ->
                states[info.id] = HomeCategoryLoadState(content = content)
            }
        }
        val selected = _homeState.value.selectedCategoryId
            ?.takeIf { it in validIds }
            ?: EMBEDDED_CATEGORIES.first().id
        _homeState.value = HomeUIState(
            categories = EMBEDDED_CATEGORIES,
            selectedCategoryId = selected,
            states = states,
        )
        ensureCategoryLoaded(selected)
    }

    /** 取出本地已有的栏目内容；没有则返回 false，由调用方决定回源。 */
    private fun hydrateCategory(categoryId: String): Boolean {
        val content = homeCategoryCache[categoryId]
            ?: _homeState.value.categories.firstOrNull { it.id == categoryId }?.content
        if (content.isNullOrEmpty()) return false
        updateCategoryState(categoryId, HomeCategoryLoadState(content = content))
        return true
    }

    private fun updateCategoryState(
        categoryId: String,
        loadState: HomeCategoryLoadState,
    ) {
        // 请求进行中就写入正文状态：分类目录可能稍后才到（点击尚未加载的推荐栏目），
        // 加载中/失败提示不能因为布局替换而丢失，内容仍按需填充。
        _homeState.update {
            it.copy(states = it.states + (categoryId to loadState))
        }
    }

    private fun networkCategoryId(rawId: String): String = "$NETWORK_CATEGORY_PREFIX$rawId"

    private fun isPromoteCategory(categoryId: String): Boolean =
        categoryId.startsWith(NETWORK_CATEGORY_PREFIX)

    private fun newHomeRequestToken(key: String): HomeRequestToken =
        HomeRequestToken(
            topologyGeneration = homeTopologyGeneration,
            requestGeneration = ++homeRequestGeneration,
            key = key,
        )

    private fun isCurrentHomeRequest(
        token: HomeRequestToken,
        requireCategory: Boolean = false,
    ): Boolean =
        token.topologyGeneration == homeTopologyGeneration &&
            activeCategoryLoads[token.key] == token &&
            (!requireCategory || _homeState.value.categories.any { it.id == token.key })

}
