package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.dinner.DinnerSource
import com.par9uet.jm.dinner.DinnerPromotionBlocked
import com.par9uet.jm.dinner.model.DinnerArticle
import com.par9uet.jm.dinner.model.DinnerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DinnerListState(
    val items: List<DinnerSummary> = emptyList(), val page: Int = 0,
    val loading: Boolean = false, val hasMore: Boolean = false, val error: String? = null,
)
data class DinnerArticleState(
    val id: Int = 0, val article: DinnerArticle? = null, val loading: Boolean = false, val error: String? = null,
    val blocked: Boolean = false,
)

class DinnerViewModel(
    private val source: DinnerSource,
    private val mode: ConnectionModeStatus,
    miscSettings: com.par9uet.jm.storage.MiscSettingsPreferences,
) : ViewModel() {
    val misc = miscSettings.misc
    private val _list = MutableStateFlow(DinnerListState())
    val list = _list.asStateFlow()
    private val _article = MutableStateFlow(DinnerArticleState())
    val article = _article.asStateFlow()
    private var listJob: Job? = null
    private var articleJob: Job? = null
    private var listGeneration = 0
    private var articleGeneration = 0
    private var failedListPage: Int? = null
    private val coverRatios = mutableMapOf<String, Float>()

    fun coverRatio(url: String): Float = coverRatios[url] ?: (4f / 3f)
    fun rememberCoverRatio(url: String, ratio: Float) {
        if (ratio.isFinite() && ratio > 0f) coverRatios[url] = ratio.coerceIn(.03f, 20f)
    }

    fun loadList(refresh: Boolean = false) {
        val state = _list.value
        if (state.loading && !refresh) return
        if (!refresh && state.page > 0 && !state.hasMore && state.error == null) return
        val page = if (refresh) 1 else failedListPage ?: (state.page + 1)
        listJob?.cancel()
        val generation = ++listGeneration
        _list.value = state.copy(loading = true, error = null)
        listJob = viewModelScope.launch {
            try {
                check(!mode.isLocalMode) { "本地模式下无法加载绅夜食堂" }
                val result = source.list(page)
                if (generation != listGeneration) return@launch
                check(page == 1 || result.items.isEmpty() || result.items.any { next -> state.items.none { it.id == next.id } }) {
                    "文章接口返回了重复页，请重试"
                }
                failedListPage = null
                _list.value = DinnerListState(
                    items = (if (page == 1) result.items else state.items + result.items).distinctBy { it.id },
                    page = page, hasMore = result.rawItemCount > 0 && page.toLong() * 12 < result.total,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == listGeneration) {
                    failedListPage = page
                    _list.update { it.copy(loading = false, error = error.message ?: "文章加载失败，请重试") }
                }
            }
        }
    }

    fun openArticle(id: Int, retry: Boolean = false) {
        if (!retry && _article.value.id == id && (_article.value.loading || _article.value.article != null)) return
        articleJob?.cancel()
        val generation = ++articleGeneration
        _article.value = DinnerArticleState(id = id, loading = true)
        articleJob = viewModelScope.launch {
            try {
                check(!mode.isLocalMode) { "本地模式下无法加载文章" }
                val result = source.article(id)
                if (generation == articleGeneration) _article.value = DinnerArticleState(id = id, article = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == articleGeneration) {
                    val blocked = error is DinnerPromotionBlocked
                    // 列表正文缺失/不完整时，详情确认的推广条目也从当前列表移除。
                    if (blocked) _list.update { it.copy(items = it.items.filterNot { item -> item.id == id }) }
                    _article.value = DinnerArticleState(id = id,
                        error = error.message ?: "文章加载失败，请重试", blocked = blocked)
                }
            }
        }
    }
}
