package com.par9uet.jm.core.concurrent

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

/** Visible loading and scrolling take priority over optional startup disk/network maintenance. */
class ForegroundWork {
    private val active = mutableSetOf<Any>()
    private val busy = MutableStateFlow(false)

    @Synchronized
    fun update(key: Any, working: Boolean) {
        if (working) active.add(key) else active.remove(key)
        busy.value = active.isNotEmpty()
    }

    @OptIn(FlowPreview::class)
    suspend fun awaitIdle() {
        // Allow the data response to reach composition and settle short gaps between visible rows.
        busy.debounce(750L).first { !it }
    }
}
