package com.par9uet.jm.ui.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ImageViewportTransformTest {
    @Test fun `portrait rotation clamps a zoomed landscape image into its new viewport`() {
        val state = ImageViewportTransform()
        state.resize(Size(1463f, 914f), 2f)
        state.transform(2f, Offset(1800f, 600f))
        assertEquals(731.5f, state.offset.x, .001f)
        assertEquals(274.5f, state.offset.y, .001f)
        state.resize(Size(914f, 1463f), 2f)
        assertEquals(2f, state.scale)
        assertEquals(457f, state.offset.x, .001f)
        assertEquals(0f, state.offset.y, .001f)
    }

    @Test fun `letterbox space is not extra panning room even at maximum zoom`() {
        val state = ImageViewportTransform()
        state.resize(Size(900f, 1400f), 10f)
        state.transform(10f, Offset(10000f, -10000f))
        assertEquals(5f, state.scale)
        assertEquals(1800f, state.offset.x, .001f)
        assertEquals(0f, state.offset.y, .001f)
        state.transform(.2f, Offset(100f, 100f))
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test fun `invalid gesture samples do not corrupt the image transform`() {
        val state = ImageViewportTransform()
        state.resize(Size(900f, 1400f), 1f)
        state.transform(Float.NaN, Offset.Zero)
        state.transform(2f, Offset(Float.POSITIVE_INFINITY, 0f))
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
    }
}
