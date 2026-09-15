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
) : NestedScrollConnection {
    var visible by mutableStateOf(true)
        private set
    private var revealJob: Job? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y != 0f) {
            visible = false
            revealJob?.cancel()
            revealJob = scope.launch {
                delay(500)
                visible = true
            }
        }
        return Offset.Zero
    }
}
