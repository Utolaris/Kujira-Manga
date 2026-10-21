package com.par9uet.jm.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicMetricsTest {
    @Test fun `unsynchronized views hide both counts including upstream fake likes`() {
        assertEquals("-", comicMetricText(0, 0))
        assertEquals("-", comicMetricText(0, 8000))
    }

    @Test fun `synchronized views preserve genuine zeros and large counts`() {
        assertEquals("0", comicMetricText(1, 0))
        assertEquals("8000", comicMetricText(10000, 8000))
        assertEquals("10000", comicMetricText(10000, 10000))
    }
}
