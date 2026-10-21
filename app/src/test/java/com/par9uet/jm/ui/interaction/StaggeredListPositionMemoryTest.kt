package com.par9uet.jm.ui.interaction

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test
import com.par9uet.jm.ui.models.ListPositionMemory

class StaggeredListPositionMemoryTest {
    @Test fun `native saver preserves each lanes different index and offset and explicit clearing is isolated`() {
        val memory = ListPositionMemory()
        val payload = listOf(intArrayOf(12, 15, 13), intArrayOf(35, 72, 10))
        val state = LazyStaggeredGridState.Saver.restore(payload)!!
        val saved = with(LazyStaggeredGridState.Saver) { SaverScope { true }.save(state)!! }
        memory.saveStaggered("dinner", saved)
        memory.save("home", 4, 20)
        val restored = LazyStaggeredGridState.Saver.restore(memory.staggeredPosition("dinner")!!)!!
        val result = with(LazyStaggeredGridState.Saver) { SaverScope { true }.save(restored) } as List<*>
        assertArrayEquals(payload[0], result[0] as IntArray)
        assertArrayEquals(payload[1], result[1] as IntArray)
        memory.clearPrefix("din")
        assertNull(memory.staggeredPosition("dinner"))
        assertEquals(ListPositionMemory.Position(4, 20), memory.position("home"))
    }
}
