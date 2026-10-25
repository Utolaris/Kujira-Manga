package com.par9uet.jm.coil

import coil.decode.DataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Mutable visibility belongs to the request, never to its cache identity. */
internal class CoverWorkGate(active: Boolean = true, visible: Boolean = true, scrolling: Boolean = false) {
    private data class Visibility(val active: Boolean, val visible: Boolean, val scrolling: Boolean) {
        val ready: Boolean get() = active && visible
        fun decodeReady(allowWhileScrolling: Boolean): Boolean = ready && (allowWhileScrolling || !scrolling)
    }

    private val visibility = MutableStateFlow(Visibility(active, visible, scrolling))
    val isReady: Boolean get() = visibility.value.ready
    fun isDecodeReady(allowWhileScrolling: Boolean = false): Boolean = visibility.value.decodeReady(allowWhileScrolling)

    fun setVisibility(active: Boolean, visible: Boolean, scrolling: Boolean = false) {
        visibility.value = Visibility(active, visible, scrolling)
    }
    suspend fun awaitReady() { visibility.first { it.ready } }
    suspend fun awaitDecodeReady(allowWhileScrolling: Boolean = false) {
        visibility.first { it.decodeReady(allowWhileScrolling) }
    }

    // UI observes only visibility boundaries, not scroll offsets.
    val changes = visibility.map { it.ready }.distinctUntilChanged()
}

/** Offscreen requests wait outside the permits, so visible covers always get the decode budget. */
internal class CoverDecodeScheduler(
    maxParallelism: Int = 1,
    private val awaitFrame: suspend () -> Unit = {},
) {
    private val permits = Semaphore(maxParallelism)

    suspend fun <T> decode(
        gate: CoverWorkGate,
        dataSource: DataSource = DataSource.NETWORK,
        block: suspend () -> T,
    ): T {
        // Disk/local cache hits must remain visible during a fling; they share the same bounded
        // background decode budget. Only newly fetched network images wait for scrolling to settle.
        val allowWhileScrolling = dataSource != DataSource.NETWORK
        while (true) {
            gate.awaitDecodeReady(allowWhileScrolling)
            permits.withPermit {
                // Visibility can change while queued behind another decode.
                if (gate.isDecodeReady(allowWhileScrolling)) {
                    val result = block()
                    // Hold the permit through publication pacing: fast disk decodes must not
                    // turn into a batch of bitmap uploads and UI state changes in one frame.
                    awaitFrame()
                    return result
                }
            }
        }
    }
}

/** Bound fetch + cache writes as well as decode; scrolling must not start a stream of misses. */
internal class CoverFetchScheduler(maxParallelism: Int = 2) {
    private val permits = Semaphore(maxParallelism)

    suspend fun <T> fetch(gate: CoverWorkGate, cached: Boolean, block: suspend () -> T): T {
        while (true) {
            gate.awaitDecodeReady(allowWhileScrolling = cached)
            permits.withPermit {
                if (gate.isDecodeReady(allowWhileScrolling = cached)) return block()
            }
        }
    }
}

internal const val CoverWorkGateParameter = "jm-cover-work-gate"
