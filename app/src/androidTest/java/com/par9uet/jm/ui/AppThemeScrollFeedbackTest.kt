package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Velocity
import com.par9uet.jm.storage.AppearanceEditor
import com.par9uet.jm.storage.AppearancePreferences
import com.par9uet.jm.storage.ColorPaletteState
import com.par9uet.jm.ui.interaction.HapticOverscrollEffect
import com.par9uet.jm.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the production theme, rather than manually installing the wrapper in a test theme. */
class AppThemeScrollFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realAppThemeInstallsHapticsAndKeepsThePlatformScrollNode() {
        val native = object : OverscrollEffect {
            override val node = object : Modifier.Node() {}
            override val isInProgress = false
            override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset) = performScroll(delta)
            override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) { performFling(velocity) }
        }
        val factory = object : OverscrollFactory {
            override fun createOverscrollEffect() = native
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }
        val appearance = object : AppearancePreferences {
            override val theme = MutableStateFlow("light")
            override val colorPalette = MutableStateFlow(ColorPaletteState("default", null, null, null, null))
            override val glassBlurLevel = MutableStateFlow(50)
            override val glassAppearance = MutableStateFlow(com.par9uet.jm.data.models.GlassAppearance())
            override val launcherDisguiseId = MutableStateFlow("default")
            override val editor: AppearanceEditor get() = error("No settings edits expected")
        }
        var observed: OverscrollFactory? = null
        compose.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides factory) {
                AppTheme(appearance) {
                    val installed = LocalOverscrollFactory.current
                    SideEffect { observed = installed }
                    Text("production theme")
                }
            }
        }
        compose.runOnIdle {
            val effect = observed!!.createOverscrollEffect()
            assertTrue(effect is HapticOverscrollEffect)
            assertSame(native.node, effect.node)
        }
    }
}
