package com.par9uet.jm.notification

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.notification.model.*
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserSessionSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One cold-start request; reads update the badge locally, without polling or resume requests. */
class NotificationUnreadCoordinator(
    private val source: NotificationSource,
    private val account: AccountRequestScope,
    private val mode: ConnectionModeStatus,
    scope: CoroutineScope,
) : NotificationUnreadStatus, NotificationUnreadUpdates {
    private val checked = AtomicBoolean(false)
    private val lock = Any()
    private val _hasUnread = MutableStateFlow(false)
    override val hasUnread = _hasUnread.asStateFlow()
    private var owner = account.sessions.value
    private var counts = NotificationUnreadCounts()
    private var revision = 0
    private val readIds = mutableSetOf<String>()

    init { scope.launch { account.sessions.collect { synchronized(lock) { syncOwner() } } } }

    private fun syncOwner() {
        val current = account.sessions.value
        if (owner == current) return
        owner = current
        revision++
        readIds.clear()
        counts = NotificationUnreadCounts()
        _hasUnread.value = false
    }

    suspend fun checkOnce() {
        if (!checked.compareAndSet(false, true)) return
        val (requestedOwner, requestedRevision) = synchronized(lock) {
            syncOwner()
            owner to revision
        }
        if (requestedOwner.accountId <= 0 || mode.isLocalMode) return
        val result = account.run(requestedOwner) { source.unreadCounts() }
        synchronized(lock) {
            syncOwner()
            // A late count must not relight an inbox already read, or leak across account changes.
            if (owner != requestedOwner || requestedRevision != revision) return
            counts = result
            _hasUnread.value = counts.hasUnread
        }
    }

    override fun read(owner: UserSessionSnapshot, id: String, type: String): Unit = synchronized(lock) {
        syncOwner()
        if (owner != this.owner || !readIds.add(id)) return
        revision++
        counts = when (type) {
            "comic_follow" -> counts.copy(tracking = (counts.tracking - 1).coerceAtLeast(0))
            "site_notice" -> counts.copy(site = (counts.site - 1).coerceAtLeast(0))
            else -> counts
        }
        _hasUnread.value = counts.hasUnread
    }

    override fun allRead(owner: UserSessionSnapshot): Unit = synchronized(lock) {
        syncOwner()
        if (owner != this.owner) return
        revision++
        counts = NotificationUnreadCounts()
        _hasUnread.value = false
    }
}
