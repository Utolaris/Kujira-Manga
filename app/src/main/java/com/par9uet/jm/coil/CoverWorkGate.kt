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
    val isScrolling: Boolean get() = visibility.value.scrolling
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

/**
 * Offscreen requests wait outside the permits, so visible covers always get the decode budget.
 * Disk hits can use a wider concurrency budget and skip frame-by-frame pacing when the grid is
 * idle ([paceIdle] = false), while scrolling keeps per-frame pacing to avoid upload spikes.
 */
internal class CoverDecodeScheduler(
    maxParallelism: Int = 1,
    diskMaxParallelism: Int = maxParallelism,
    private val paceIdle: Boolean = true,
    private val awaitFrame: suspend () -> Unit = {},
) {
    private val networkPermits = Semaphore(maxParallelism)
    private val diskPermits = if (diskMaxParallelism == maxParallelism) {
        networkPermits
    } else {
        Semaphore(diskMaxParallelism)
    }

    suspend fun <T> decode(
        gate: CoverWorkGate,
        dataSource: DataSource = DataSource.NETWORK,
        block: suspend () -> T,
    ): T {
        // Disk/local cache hits must remain visible during a fling; they share a bounded
        // background decode budget. Newly fetched network images wait for scrolling to settle.
        val isDisk = dataSource != DataSource.NETWORK
        val allowWhileScrolling = isDisk
        val permits = if (isDisk) diskPermits else networkPermits
        while (true) {
            gate.awaitDecodeReady(allowWhileScrolling)
            permits.withPermit {
                // Visibility can change while queued behind another decode.
                if (gate.isDecodeReady(allowWhileScrolling)) {
                    val result = block()
                    // Hold the permit through publication pacing during scroll (or when paceIdle
                    // is enabled): fast disk decodes during a fling will not burst into one frame,
                    // while idle page entry publishes concurrently.
                    if (paceIdle || gate.isScrolling) {
                        awaitFrame()
                    }
                    return result
                }
            }
        }
    }
}

/**
 * Bound network fetch + cache writes separately from local disk reads so slow network requests
 * never stall cached covers (e.g. entering Favorites).
 */
internal class CoverFetchScheduler(
    maxParallelism: Int = 2,
    diskMaxParallelism: Int = 4,
) {
    private val networkPermits = Semaphore(maxParallelism)
    private val diskPermits = Semaphore(diskMaxParallelism)

    suspend fun <T> fetch(gate: CoverWorkGate, cached: Boolean, block: suspend () -> T): T {
        val permits = if (cached) diskPermits else networkPermits
        while (true) {
            gate.awaitDecodeReady(allowWhileScrolling = cached)
            permits.withPermit {
                if (gate.isDecodeReady(allowWhileScrolling = cached)) return block()
            }
        }
    }
}

internal const val CoverWorkGateParameter = "jm-cover-work-gate"
