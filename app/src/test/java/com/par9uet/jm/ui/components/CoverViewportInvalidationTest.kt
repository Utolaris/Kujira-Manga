package com.par9uet.jm.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverViewportInvalidationTest {
    @Test fun `moving the visible window invalidates only covers entering or leaving`() {
        val keys = mutableStateOf<Set<Any>>((0 until 20).toSet())
        val invalidated = mutableSetOf<Int>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            (0..20).forEach { key ->
                val visible = coverItemVisibility(keys, key)
                observer.observeReads(key, { invalidated += it }) { visible.value }
            }
            keys.value = (1..20).toSet()
            Snapshot.sendApplyNotifications()
            assertEquals(setOf(0, 20), invalidated)
        } finally { observer.stop(); observer.clear() }
    }

}
