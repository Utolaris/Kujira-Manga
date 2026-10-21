package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.MotionButton
import com.par9uet.jm.ui.screens.DetailIconAction
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import com.par9uet.jm.ui.interaction.motionEnabled
import org.junit.Rule
import org.junit.Test

class PressFeedbackRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun whiteWidth(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var left = image.width
        var right = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = pixels[x, y]
            if (color.red > .6f && color.green > .6f && color.blue > .6f) {
                left = minOf(left, x); right = maxOf(right, x)
            }
        }
        return (right - left + 1).coerceAtLeast(0)
    }

    @Test fun pressShrinksDrawnButtonAndCancelRestoresItWithoutClicking() {
        assumeTrue("System animations are enabled", motionEnabled())
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp, 100.dp).background(Color.Black).testTag("canvas"), contentAlignment = Alignment.Center) {
                    MotionButton(onClick = { clicks++ }, modifier = Modifier.size(120.dp, 48.dp).testTag("press-button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("按钮") }
                }
            }
        }
        val before = whiteWidth(compose.onNodeWithTag("canvas").captureToImage())
        compose.onNodeWithTag("press-button").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        val pressed = whiteWidth(compose.onNodeWithTag("canvas").captureToImage())
        assertTrue("pressed=$pressed before=$before", pressed < before * .99f && pressed > before * .93f)
        compose.onNodeWithTag("press-button").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(240)
        val restored = whiteWidth(compose.onNodeWithTag("canvas").captureToImage())
        assertTrue(kotlin.math.abs(restored - before) <= 2)
        assertEquals(0, clicks)
    }

    @Test fun favoriteHeartPulsesOnceAndReturnsToItsOriginalSize() {
        assumeTrue("System animations are enabled", motionEnabled())
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(100.dp).background(Color.Black).testTag("canvas"), contentAlignment = Alignment.Center) {
                    DetailIconAction(Icons.Filled.Favorite, "收藏测试", pulseOnClick = true, tint = Color.White,
                        size = 48.dp, onClick = { clicks++ })
                }
            }
        }
        val before = whiteWidth(compose.onNodeWithTag("canvas").captureToImage())
        compose.onNodeWithContentDescription("收藏测试").performClick()
        compose.mainClock.advanceTimeBy(128)
        val peak = whiteWidth(compose.onNodeWithTag("canvas").captureToImage())
        assertTrue("peak=$peak before=$before", peak > before * 1.1f)
        compose.mainClock.advanceTimeBy(256)
        val restored = whiteWidth(compose.onNodeWithTag("canvas").captureToImage())
        assertTrue(kotlin.math.abs(restored - before) <= 2)
        assertEquals(1, clicks)
    }
}
