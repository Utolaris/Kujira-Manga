package com.par9uet.jm.ui.glass

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.data.models.GlassAppearance
import com.par9uet.jm.data.models.GlassEffect
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedLight
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SoftGlassEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun softMaterialCompilesForSurfacesAndImagesAcrossSizesAndStrengths() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        compose.runOnUiThread {
            for (dark in listOf(false, true)) for (level in listOf(16, 84, 168)) {
                for (density in listOf(1f, 3f)) {
                    val material = GlassMaterialStyle.Default.withAppearance(GlassAppearance(GlassEffect.Soft, softLevel = level), density)
                    val padding = material.blurRadius.value * density
                    assertNotNull(createGlassRenderEffect(material, dark, density, 300 + padding * 2, 80 + padding * 2, padding, 28f))
                    val cache = GlassImageEffectCache(material, dark, density)
                    assertSame(cache.effectFor(400f, 600f), cache.effectFor(400f, 600f))
                    assertNotSame(cache.effectFor(400f, 600f), cache.effectFor(600f, 400f))
                }
            }
            assertNull(createGlassRenderEffect(GlassMaterialStyle(blurRadius = 0.dp), false, 1f, 300f, 80f))
        }
    }

    @Test fun sourceAndOverlayHostsReceiveLiveMaterialChanges() {
        val setting = mutableStateOf(GlassAppearance())
        var source: GlassAppearance? = null
        var overlay: GlassAppearance? = null
        var nested: GlassAppearance? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGlassAppearance provides setting.value,
                    LocalMainNavController provides rememberNavController(), LocalExtendedColors provides extendedLight) {
                    GlassCaptureHost(sourceContent = {
                        val value = LocalGlassAppearance.current
                        SideEffect { source = value }
                        Text("source")
                    }, overlayContent = {
                        val value = LocalGlassAppearance.current
                        SideEffect { overlay = value }
                        GlassCaptureHost(sourceContent = { Text("nested source") }, overlayContent = {
                            val inner = LocalGlassAppearance.current
                            SideEffect { nested = inner }
                        })
                    })
                }
            }
        }
        compose.runOnIdle {
            assertEquals(setting.value, source)
            assertEquals(setting.value, overlay)
            setting.value = GlassAppearance(GlassEffect.Soft, blurLevel = 25, softLevel = 140)
        }
        compose.runOnIdle {
            assertEquals(setting.value, source)
            assertEquals(setting.value, overlay)
            assertEquals(setting.value, nested)
        }
    }
}
