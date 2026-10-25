package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Observes vertical motion, including flings, without consuming the page's gestures. */
internal class ScrollAwareNavigationState(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : NestedScrollConnection {
    var visible by mutableStateOf(true)
        private set
    private var revealJob: Job? = null
    private var lastMotionMillis = 0L

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y != 0f) {
            visible = false
            lastMotionMillis = nowMillis()
            // Fling deltas arrive every frame. Keep one waiter instead of cancelling and
            // allocating a new coroutine/timer for every subpixel movement.
            if (revealJob?.isActive != true) {
                revealJob = scope.launch {
                    while (true) {
                        val remaining = 500L - (nowMillis() - lastMotionMillis)
                        if (remaining <= 0L) break
                        delay(remaining)
                    }
                    visible = true
                }
            }
        }
        return Offset.Zero
    }

    fun dispose() { revealJob?.cancel(); revealJob = null; visible = true }
}
