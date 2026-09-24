package com.par9uet.jm.favorites.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes local favorite edits with the complete local-to-network transition. */
class LocalFavoriteOperationGate {
    private val mutex = Mutex()
    private val transitioning = AtomicBoolean(false)
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning = _isTransitioning.asStateFlow()

    @Synchronized
    fun beginTransition(): Boolean {
        if (!transitioning.compareAndSet(false, true)) return false
        _isTransitioning.value = true
        return true
    }

    @Synchronized
    fun endTransition() {
        transitioning.set(false)
        _isTransitioning.value = false
    }

    fun isTransitioning(): Boolean = transitioning.get()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
