package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.par9uet.jm.data.models.GlassAppearance
import com.par9uet.jm.data.models.GlassEffect
import com.par9uet.jm.ui.screens.GlassAppearanceControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlassAppearanceControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rejectedStrengthChangeRestoresThePersistedThumbPosition() {
        var requested = 0
        compose.setContent {
            MaterialTheme {
                GlassAppearanceControls(GlassAppearance(GlassEffect.Soft), {}, { requested = it })
            }
        }
        compose.onNodeWithTag("glass-intensity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(168f) }
        compose.runOnIdle { assertEquals(168, requested) }
        compose.onNodeWithTag("glass-intensity-slider").assertRangeInfoEquals(ProgressBarRangeInfo(84f, 16f..168f))
    }

    @Test fun selectionSwitchesLabelRangeAndRestoresEachModesValueWithoutDisplayingNumbers() {
        val state = mutableStateOf(GlassAppearance(blurLevel = 35))
        compose.setContent {
            MaterialTheme {
                GlassAppearanceControls(state.value,
                    onEffectChange = { state.value = state.value.copy(effect = it) },
                    onLevelChange = {
                        state.value = if (state.value.effect == GlassEffect.Soft) state.value.copy(softLevel = it)
                            else state.value.copy(blurLevel = it)
                    })
            }
        }
        compose.onNodeWithText("高斯模糊").assertIsSelected()
        compose.onNodeWithTag("glass-intensity-slider").assertRangeInfoEquals(ProgressBarRangeInfo(35f, 0f..100f))
        compose.onNodeWithText("柔光玻璃").performClick().assertIsSelected()
        compose.onNodeWithText("柔光玻璃强度").assertExists()
        compose.onNodeWithText("高斯模糊强度").assertDoesNotExist()
        compose.onNodeWithText("通透").assertExists()
        compose.onNodeWithText("柔和").assertExists()
        compose.onNodeWithTag("glass-intensity-slider").assertRangeInfoEquals(ProgressBarRangeInfo(84f, 16f..168f))
        for (number in listOf("16", "84", "168")) compose.onNodeWithText(number).assertDoesNotExist()
        compose.onNodeWithTag("glass-intensity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(168f) }
        compose.runOnIdle { assertEquals(168, state.value.softLevel) }
        compose.onNodeWithText("高斯模糊").performClick()
        compose.onNodeWithText("高斯模糊强度").assertExists()
        compose.onNodeWithTag("glass-intensity-slider").assertRangeInfoEquals(ProgressBarRangeInfo(35f, 0f..100f))
        compose.onNodeWithText("柔光玻璃").performClick()
        compose.onNodeWithTag("glass-intensity-slider").assertRangeInfoEquals(ProgressBarRangeInfo(168f, 16f..168f))
    }
}
