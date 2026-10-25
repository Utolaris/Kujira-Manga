package com.par9uet.jm.ui.glass

import com.par9uet.jm.data.models.GlassAppearance
import com.par9uet.jm.data.models.GlassEffect
import org.junit.Assert.*
import org.junit.Test

class GlassMaterialSettingsTest {
    @Test fun `Gaussian preserves existing levels while soft glass uses its independent range`() {
        for ((level, radius) in listOf(0 to 0f, 50 to 18f, 100 to 36f)) {
            val style = GlassMaterialStyle.Default.withAppearance(GlassAppearance(blurLevel = level, softLevel = 168))
            assertEquals(GlassEffect.Gaussian, style.effect)
            assertEquals(radius, style.blurRadius.value, 0.001f)
        }
        for ((level, radius) in listOf(-1 to 6.4f, 16 to 6.4f, 84 to 33.6f, 168 to 67.2f, 999 to 67.2f)) {
            val appearance = GlassAppearance(GlassEffect.Soft, blurLevel = 0, softLevel = level)
            val style = GlassMaterialStyle.Reader.withAppearance(appearance)
            assertEquals(GlassEffect.Soft, style.effect)
            assertEquals(radius, style.blurRadius.value, 0.001f)
            assertEquals(style, GlassMaterialStyle.Default.withAppearance(appearance))
        }
    }

    @Test fun `high density does not multiply the reference soft blur pixel cost`() {
        for (density in listOf(1f, 2f, 3.5f)) {
            val material = GlassMaterialStyle.Default.withAppearance(GlassAppearance(GlassEffect.Soft), density)
            assertEquals(33.6f, material.blurRadius.value * density, 0.001f)
            val gaussian = GlassMaterialStyle.Default.withAppearance(GlassAppearance(), density)
            assertEquals(18f, gaussian.blurRadius.value, 0f)
        }
    }

    @Test fun `softness changes blur and color treatment without pretending to be opacity`() {
        for (dark in listOf(false, true)) {
            val clear = softGlassProfile(16, dark)
            val soft = softGlassProfile(168, dark)
            assertTrue(clear.saturation > soft.saturation)
            assertEquals(clear.tintAlpha, soft.tintAlpha, 0f)
            assertTrue(clear.grain > 0f)
            assertTrue(clear.contrast in 0.9f..1f)
        }
        assertNotEquals(softGlassProfile(84, false), softGlassProfile(84, true))
    }
}
