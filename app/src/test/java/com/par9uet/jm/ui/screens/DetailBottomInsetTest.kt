package com.par9uet.jm.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailBottomInsetTest {
    @Test fun `only full width controls reserve the bottom bar and all modes retain system insets`() {
        for (systemInset in listOf(0.dp, 24.dp)) {
            assertEquals(systemInset + 8.dp, detailLayoutBottomInset(true, false, systemInset, 64.dp))
            assertEquals(systemInset + 88.dp, detailLayoutBottomInset(true, true, systemInset, 64.dp))
            assertEquals(systemInset + 88.dp, detailLayoutBottomInset(false, false, systemInset, 64.dp))
            assertEquals(systemInset + 88.dp, detailLayoutBottomInset(false, true, systemInset, 64.dp))
        }
    }
}
