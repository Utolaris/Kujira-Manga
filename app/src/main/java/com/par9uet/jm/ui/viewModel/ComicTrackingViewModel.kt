package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.notification.NotificationSource
import com.par9uet.jm.session.AccountRequestScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ComicTrackingState(
    val id: Int = 0, val eligible: Boolean = false,
    val subscribed: Boolean? = null, val busy: Boolean = false, val error: String? = null,
)

class ComicTrackingViewModel(
    private val source: NotificationSource,
    private val account: AccountRequestScope,
    private val mode: ConnectionModeStatus,
) : ViewModel() {
    private val _state = MutableStateFlow(ComicTrackingState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            account.sessions.collect {
                job?.cancel()
                generation++
                _state.value = _state.value.copy(subscribed = null, busy = false, error = null)
                refresh()
            }
        }
    }

    fun bind(id: Int, chapterIds: List<Int>) {
        // SDK 为无目录的单本补出自身章节；只有独立章节才是可追踪的合集。
        val isCollection = chapterIds.any { it > 0 && it != id }
        if (_state.value.id == id && _state.value.eligible == isCollection) {
            refresh()
            return
        }
        job?.cancel()
        generation++
        _state.value = ComicTrackingState(id = id, eligible = isCollection)
        refresh()
    }

    fun refresh() {
        // 自动读状态时不向未登录/本地收藏模式用户展开错误；点击后再给出操作提示。
        if (account.sessions.value.accountId <= 0 || mode.isLocalMode) {
            _state.value = _state.value.copy(subscribed = null, busy = false, error = null)
            return
        }
        request(toggle = false)
    }
    fun toggle() = request(toggle = _state.value.subscribed != null)

    private fun request(toggle: Boolean) {
        val current = _state.value
        if (!current.eligible || current.id <= 0 || current.busy) return
        val owner = account.sessions.value
        val requestGeneration = ++generation
        _state.value = current.copy(busy = true, error = null, subscribed = if (toggle) null else current.subscribed)
        job = viewModelScope.launch {
            try {
                check(!mode.isLocalMode) { "本地收藏模式下无法订阅漫画" }
                check(owner.accountId > 0) { "请先登录后订阅漫画" }
                val subscribed = account.run(owner) {
                    if (toggle) source.toggleTracking(current.id)
                    source.tracking(current.id)
                }
                if (owner == account.sessions.value && requestGeneration == generation) {
                    _state.value = current.copy(subscribed = subscribed)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (owner == account.sessions.value && requestGeneration == generation) {
                    // POST 结果不明或复查失败时只重查状态，绝不自动重放切换操作。
                    _state.value = current.copy(subscribed = null, error = error.message ?: "订阅状态获取失败")
                }
            }
        }
    }
}
