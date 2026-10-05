package com.par9uet.jm.favorites.sync

import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.data.FAVORITE_SCOPE_ALL
import com.par9uet.jm.favorites.data.causeChainText
import com.par9uet.jm.favorites.data.toFavoriteSyncError
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.session.withAuthenticationRecovery
import com.par9uet.jm.favorites.sync.FavoriteSyncProgress
import com.par9uet.jm.favorites.sync.FavoriteSyncReport
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FavoriteSyncRequestKind {
    AUTO,
    MANUAL,
    FORCE,
}

interface FavoriteSyncRequester {
    val state: StateFlow<FavoriteSyncUiState>

    fun request(kind: FavoriteSyncRequestKind, folderId: Int = 0)

    /** Starts a full cache build for an account that has never completed one. */
    suspend fun initializeForLogin()
}

/** Owns the application sync job, its session, progress and shared completion cooldown. */
class FavoriteSyncController(
    private val session: FavoriteSession,
    private val syncOperation: suspend (
        snapshot: FavoriteSessionSnapshot,
        folderId: Int,
        force: Boolean,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ) -> NetWorkResult<FavoriteSyncReport>,
    private val applicationScope: CoroutineScope,
    private val localMode: com.par9uet.jm.core.model.ConnectionModeStatus,
    private val autoSyncCoordinator: FavoriteAutoSyncCoordinator = FavoriteAutoSyncCoordinator(),
    private val hasFullSnapshot: suspend (Int) -> Boolean = { false },
    private val onCacheInitialized: () -> Unit = {},
    private val lastSuccessfulSyncAt: suspend (Int) -> Long? = { null },
) : FavoriteSyncRequester {
    private val lock = Any()
    private val _state = MutableStateFlow(FavoriteSyncUiState())
    override val state: StateFlow<FavoriteSyncUiState> = _state.asStateFlow()
    private var observedSession = session.snapshot()
    private var requestGeneration = 0L
    private var syncJob: Job? = null
    private var automaticRequestJob: Job? = null
    private var pendingInitialAccount: Int? = null

    init {
        applicationScope.launch {
            session.sessionFlow.distinctUntilChanged().collect {
                synchronized(lock) { refreshSession() }
            }
        }
        applicationScope.launch {
            localMode.isLocalModeFlow.collect { isLocal ->
                if (isLocal) synchronized(lock) {
                    requestGeneration++
                    syncJob?.cancel()
                    syncJob = null
                    automaticRequestJob?.cancel()
                    automaticRequestJob = null
                    pendingInitialAccount = null
                    autoSyncCoordinator.reset()
                    _state.value = FavoriteSyncUiState()
                }
            }
        }
    }

    override suspend fun initializeForLogin() {
        val snapshot = session.snapshot()
        if (snapshot.accountId <= 0 || localMode.isLocalMode || hasFullSnapshot(snapshot.accountId)) return
        synchronized(lock) {
            refreshSession()
            if (!session.isCurrent(snapshot) || localMode.isLocalMode) return
            pendingInitialAccount = snapshot.accountId
            if (!_state.value.isSyncing) startSync(FAVORITE_SCOPE_ALL, force = true)
        }
    }

    override fun request(kind: FavoriteSyncRequestKind, folderId: Int) {
        synchronized(lock) {
            refreshSession()
            // 本地模式不打远端同步，避免登录态被踢时把 401 风暴拉满。
            if (localMode.isLocalMode) return
            if (observedSession.accountId <= 0) return
            when (kind) {
                FavoriteSyncRequestKind.AUTO -> {
                    requestAutomaticSync(folderId)
                }
                // Explicit refresh remains available during the automatic cooldown.
                // Every request still shares the same single sync slot.
                FavoriteSyncRequestKind.MANUAL, FavoriteSyncRequestKind.FORCE -> {
                    if (_state.value.isSyncing) return
                    val force = kind == FavoriteSyncRequestKind.FORCE
                    startSync(if (force) FAVORITE_SCOPE_ALL else folderId, force)
                }
            }
        }
    }

    private fun refreshSession() {
        val current = session.snapshot()
        if (current == observedSession) return
        val accountChanged = current.accountId != observedSession.accountId
        observedSession = current
        requestGeneration++
        syncJob?.cancel()
        syncJob = null
        automaticRequestJob?.cancel()
        automaticRequestJob = null
        pendingInitialAccount = null
        if (accountChanged) autoSyncCoordinator.reset()
        _state.value = FavoriteSyncUiState()
    }

    private fun requestAutomaticSync(folderId: Int) {
        if (_state.value.isSyncing || automaticRequestJob?.isActive == true) return
        val snapshot = observedSession
        lateinit var job: Job
        job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                val completedAt = lastSuccessfulSyncAt(snapshot.accountId)
                synchronized(lock) {
                    if (automaticRequestJob !== job || !session.isCurrent(snapshot) || localMode.isLocalMode) {
                        return@synchronized
                    }
                    val result = autoSyncCoordinator.request(folderId, _state.value.isSyncing, completedAt)
                    if (result is FavoriteAutoRequestResult.StartNow) startSync(result.folderId, force = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Failed storage reads must not start an unguarded request or crash startup.
                logError("FavoritesSync", "Cannot read completion cooldown: ${error.message}")
            } finally {
                synchronized(lock) {
                    if (automaticRequestJob === job) automaticRequestJob = null
                }
            }
        }
        automaticRequestJob = job
        job.start()
    }

    private fun startSync(folderId: Int, force: Boolean) {
        val snapshot = observedSession
        val generation = ++requestGeneration
        _state.value = FavoriteSyncUiState(isSyncing = true, isForceRefresh = force)
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            var failure: NetWorkResult.Error? = null
            var succeeded = false
            var notifyCacheInitialized = false
            try {
                val onProgress: (FavoriteSyncProgress) -> Unit = { progress ->
                    synchronized(lock) {
                        if (isCurrentRequest(snapshot, generation)) {
                            _state.update {
                                it.copy(completed = progress.completed, total = progress.total, phase = progress.phase)
                            }
                        }
                    }
                }
                val result = withAuthenticationRecovery(
                    isCurrent = { session.isCurrent(snapshot) },
                    recover = { session.recoverExpiredSession(snapshot) },
                ) {
                    syncOperation(snapshot, folderId, force, onProgress)
                }
                when (result) {
                    is NetWorkResult.Success -> succeeded = true
                    is NetWorkResult.Error -> failure = result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.toFavoriteSyncError()
            } finally {
                synchronized(lock) {
                    if (isCurrentRequest(snapshot, generation)) {
                        syncJob = null
                        if (failure != null) {
                            val f = failure
                            val chain = f.cause?.causeChainText() ?: f.message
                            logError(
                                "FavoritesSync",
                                "sync FAILED folder=$folderId force=$force " +
                                    "kind=${f.kind} code=${f.code} message=${f.message} cause=$chain",
                            )
                        }
                        _state.value = if (failure == null) {
                            FavoriteSyncUiState()
                        } else {
                            _state.value.copy(
                                isSyncing = false,
                                errorMessage = failure.message,
                                errorKind = failure.kind,
                            )
                        }
                        if (succeeded) autoSyncCoordinator.onSyncSucceeded()
                        if (succeeded && pendingInitialAccount == snapshot.accountId) {
                            if (force && folderId == FAVORITE_SCOPE_ALL) {
                                pendingInitialAccount = null
                                notifyCacheInitialized = true
                            } else {
                                startSync(FAVORITE_SCOPE_ALL, force = true)
                            }
                        }
                        // Automatic requests are never replayed after success or failure.
                        // A new entry after the completion cooldown may request the next sync.
                    }
                }
                if (notifyCacheInitialized && session.isCurrent(snapshot)) onCacheInitialized()
            }
        }
        syncJob = job
        job.start()
    }

    private fun isCurrentRequest(snapshot: FavoriteSessionSnapshot, generation: Long): Boolean =
        requestGeneration == generation && session.isCurrent(snapshot)
}
