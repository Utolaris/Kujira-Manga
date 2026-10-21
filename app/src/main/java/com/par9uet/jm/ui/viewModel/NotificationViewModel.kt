package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.notification.NotificationSource
import com.par9uet.jm.notification.model.*
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InboxState(
    val filter: NotificationFilter = NotificationFilter.All,
    val items: List<InboxMessage> = emptyList(),
    val page: Int = 0, val hasMore: Boolean = false,
    val loading: Boolean = false, val markingAll: Boolean = false,
    val readingIds: Set<String> = emptySet(), val expandedId: String? = null,
    val error: String? = null,
)

class NotificationViewModel(
    private val source: NotificationSource,
    private val account: AccountRequestScope,
    private val mode: ConnectionModeStatus,
    private val unread: NotificationUnreadUpdates,
) : ViewModel() {
    private val _state = MutableStateFlow(InboxState())
    val state = _state.asStateFlow()
    private var listJob: Job? = null
    private val mutationJobs = mutableListOf<Job>()
    private var generation = 0
    private var entered = false
    private val readIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            account.sessions.collect {
                generation++
                listJob?.cancel()
                mutationJobs.forEach { it.cancel() }
                mutationJobs.clear()
                readIds.clear()
                _state.value = InboxState()
                if (entered) load()
            }
        }
    }

    fun enter() {
        entered = true
        selectFilter(NotificationFilter.All)
    }

    fun leave() { entered = false }

    fun selectFilter(filter: NotificationFilter) {
        if (_state.value.markingAll) return
        listJob?.cancel()
        generation++
        _state.value = InboxState(filter = filter)
        load()
    }

    fun load() {
        val current = _state.value
        if (current.loading || current.markingAll || (current.page > 0 && !current.hasMore)) return
        val owner = account.sessions.value
        val requestGeneration = generation
        val page = current.page + 1
        _state.update { it.copy(loading = true, error = null) }
        listJob = viewModelScope.launch {
            try {
                requireRemote(owner)
                val result = account.run(owner) { source.list(current.filter, page) }
                if (!isCurrent(owner, requestGeneration)) return@launch
                check(page == 1 || result.items.isEmpty() || result.items.any { item -> current.items.none { it.id == item.id } }) {
                    "通知接口返回了重复页，请重试"
                }
                _state.update { state -> state.copy(
                    items = (if (page == 1) result.items else state.items + result.items)
                        .distinctBy { it.id }.map { if (it.id in readIds) it.copy(read = true) else it },
                    page = page, hasMore = result.hasMore, loading = false,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (isCurrent(owner, requestGeneration)) _state.update { it.copy(loading = false, error = error.message ?: "通知加载失败") }
            }
        }
    }

    fun open(id: String) {
        val message = _state.value.items.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(expandedId = if (it.expandedId == id) null else id) }
        if (message.read || id in _state.value.readingIds || _state.value.markingAll) return
        val owner = account.sessions.value
        _state.update { it.copy(readingIds = it.readingIds + id, error = null) }
        mutationJobs.removeAll { it.isCompleted }
        mutationJobs += viewModelScope.launch {
            try {
                requireRemote(owner)
                account.run(owner) { source.markRead(id) }
                if (owner != account.sessions.value) return@launch
                readIds += id
                unread.read(owner, id, message.type)
                _state.update { it.copy(items = it.items.map { item -> if (item.id == id) item.copy(read = true) else item }) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (owner == account.sessions.value) _state.update { it.copy(error = error.message ?: "标记已读失败，点击通知重试") }
            } finally {
                if (owner == account.sessions.value) _state.update { it.copy(readingIds = it.readingIds - id) }
            }
        }
    }

    fun markAllRead() {
        if (_state.value.markingAll || _state.value.loading || _state.value.readingIds.isNotEmpty()) return
        val owner = account.sessions.value
        _state.update { it.copy(markingAll = true, error = null) }
        mutationJobs.removeAll { it.isCompleted }
        mutationJobs += viewModelScope.launch {
            try {
                requireRemote(owner)
                account.run(owner) { source.markAllRead() }
                if (owner != account.sessions.value) return@launch
                readIds += _state.value.items.map { it.id }
                unread.allRead(owner)
                _state.update { it.copy(markingAll = false, items = it.items.map { item -> item.copy(read = true) }) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (owner == account.sessions.value) {
                    _state.update { it.copy(markingAll = false, error = error.message ?: "一键已读未完成，请重试") }
                }
            }
        }
    }

    private fun requireRemote(owner: UserSessionSnapshot) {
        check(!mode.isLocalMode) { "本地模式下无法加载通知" }
        check(owner.accountId > 0) { "请先登录后查看通知" }
    }
    private fun isCurrent(owner: UserSessionSnapshot, requestGeneration: Int) =
        owner == account.sessions.value && requestGeneration == generation
}
