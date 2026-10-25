package com.par9uet.jm.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.GlassAppearance
import com.par9uet.jm.data.models.GlassEffect
import com.par9uet.jm.ui.components.CoverBackdropLayers
import com.par9uet.jm.ui.glass.GlassImageEffectCache
import com.par9uet.jm.ui.glass.GlassMaterialStyle
import com.par9uet.jm.ui.glass.withAppearance
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CoverBackdropGradientTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tagExpansionKeepsThePhoneImageScaleAndTopAnchorFixed() {
        val height = mutableStateOf(140.dp)
        val drawnSize = AtomicReference<Size>()
        val painter = object : Painter() {
            override val intrinsicSize = Size(300f, 400f)
            override fun DrawScope.onDraw() {
                drawnSize.set(size)
                drawRect(Color.Red)
                drawRect(Color.Blue, topLeft = Offset(0f, size.height * .25f),
                    size = Size(size.width, size.height * .75f))
            }
        }
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                CoverBackdropLayers(painter, Modifier, Color.White, Color(0xFFFAFAFA),
                    Modifier.size(180.dp, height.value).testTag("fixed-phone-image"), imageAspectRatio = .75f)
            }
        }
        var original: Color? = null
        for (value in listOf(140.dp, 200.dp, 320.dp, 140.dp)) {
            compose.runOnIdle { height.value = value }
            val pixels = compose.onNodeWithTag("fixed-phone-image").captureToImage().toPixelMap()
            val sample = pixels[pixels.width / 2, pixels.width / 6]
            val imageSize = requireNotNull(drawnSize.get())
            assertEquals(imageSize.width / .75f, imageSize.height, 1f)
            assertEquals(pixels.width.toFloat(), imageSize.width, 1f)
            original?.let {
                assertEquals("No downward recrop of the top image landmark", it.red, sample.red, .01f)
                assertEquals(it.blue, sample.blue, .01f)
            }
            original = sample
        }
    }

    @Test fun bothMaterialsFadeContinuouslyToTheExactCommentBackground() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val mode = mutableStateOf(GlassEffect.Gaussian)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            val material = GlassMaterialStyle.Default.withAppearance(GlassAppearance(mode.value), density)
            val cache = remember(material, dark.value, density) { GlassImageEffectCache(material, dark.value, density) }
            CoverBackdropLayers(ColorPainter(Color.Red), Modifier.graphicsLayer {
                renderEffect = cache.effectFor(size.width, size.height)?.asComposeRenderEffect()
            }, if (dark.value) Color(0xFF121318) else Color.White,
                if (dark.value) Color(0xFF1E1F25) else Color(0xFFFAFAFA),
                Modifier.size(100.dp, 200.dp).testTag("gradient"))
        }
        for (effect in GlassEffect.entries) for (isDark in listOf(false, true)) {
            compose.runOnIdle { mode.value = effect; dark.value = isDark }
            val pixels = compose.onNodeWithTag("gradient").captureToImage().toPixelMap()
            val background = if (isDark) Color(0xFF121318) else Color.White
            fun difference(color: Color) = abs(color.red - background.red) +
                abs(color.green - background.green) + abs(color.blue - background.blue)
            val distances = listOf(.45f, .6f, .75f, .9f, .99f).map {
                difference(pixels[pixels.width / 2, ((pixels.height - 1) * it).toInt()])
            }
            assertTrue("The fade must contain a gradual transition", distances.first() > distances.last() + .05f)
            assertTrue("The fade must approach the page without a second band", distances.zipWithNext().all { (a, b) -> b <= a + .02f })
            assertTrue("The final region must match the comments", distances.last() < .02f)
        }
    }
}
