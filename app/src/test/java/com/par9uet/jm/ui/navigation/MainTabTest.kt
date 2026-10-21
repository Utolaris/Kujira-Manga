package com.par9uet.jm.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabTest {
    @Test
    fun `main tabs keep the required order`() {
        assertEquals(
            listOf(MainTab.Home, MainTab.Collect, MainTab.Settings),
            MainTab.ordered,
        )
    }
}
