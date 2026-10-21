package com.par9uet.jm.ui.interaction

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshotFlow
import com.par9uet.jm.ui.models.ListPositionMemory
import kotlinx.coroutines.flow.distinctUntilChanged

/** Use the platform Saver to preserve every lane, rather than rebuilding a waterfall from one index. */
@Composable
internal fun rememberRestoringStaggeredGridState(
    key: String,
    memory: ListPositionMemory = LocalListPositionMemory.current,
): LazyStaggeredGridState {
    val state = remember(key, memory) {
        memory.staggeredPosition(key)?.let(LazyStaggeredGridState.Saver::restore) ?: LazyStaggeredGridState()
    }
    fun save() {
        if (state.layoutInfo.totalItemsCount == 0) return
        with(LazyStaggeredGridState.Saver) {
            SaverScope { true }.save(state)?.let { memory.saveStaggered(key, it) }
        }
    }
    LaunchedEffect(state, key, memory) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { save() }
    }
    DisposableEffect(state, key, memory) { onDispose { save() } }
    return state
}
