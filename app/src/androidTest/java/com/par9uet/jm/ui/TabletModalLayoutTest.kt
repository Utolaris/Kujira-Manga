package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.ui.screens.AllGridColumnSliderDialog
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.navigation.LocalMainNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TabletModalLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rotationKeepsThreeQuarterWidthAndDraftDespiteLegacyCallerSizes() {
        var viewport by mutableStateOf(DpSize(1200.dp, 800.dp))
        var useSettingsWidth by mutableStateOf(false)
        var tablet by mutableStateOf(true)
        var measuredWidth = 0
        compose.setContent {
            val window = remember {
                object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize: IntSize
                        get() = IntSize(viewport.width.value.toInt(), viewport.height.value.toInt())
                }
            }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalWindowInfo provides window,
                    LocalTabletLayoutEnabled provides tablet,
                    LocalMainNavController provides rememberNavController(),
                ) {
                    Box(Modifier.requiredSize(viewport.width, viewport.height)) {
                        var draft by remember { mutableStateOf("未保存") }
                        GlassModal(
                            visible = true,
                            onDismissRequest = {},
                            modifier = (if (useSettingsWidth) Modifier.width(viewport.width - 32.dp)
                                else Modifier.widthIn(max = 420.dp))
                                .onSizeChanged { measuredWidth = it.width },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(draft)
                                TextButton(onClick = { draft = "旋转后保留" }) { Text("修改草稿") }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(900, measuredWidth) }
        compose.onNodeWithText("修改草稿").performClick()
        compose.runOnIdle { viewport = DpSize(800.dp, 1200.dp) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(600, measuredWidth) }
        compose.onNodeWithText("旋转后保留").assertIsDisplayed()
        compose.runOnIdle {
            useSettingsWidth = true
            viewport = DpSize(1200.dp, 800.dp)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(900, measuredWidth) }
        compose.runOnIdle { useSettingsWidth = false; tablet = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(420, measuredWidth) }
    }

    @Test
    fun phoneSettingsDialogKeepsEqualSixteenDpSideGaps() {
        compose.setContent {
            val window = remember {
                object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize = IntSize(360, 800)
                }
            }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalWindowInfo provides window,
                    LocalTabletLayoutEnabled provides false,
                    LocalMainNavController provides rememberNavController(),
                ) {
                    Box(Modifier.requiredSize(360.dp, 800.dp).testTag("phone-viewport")) {
                        AllGridColumnSliderDialog(true, 0, 0, 0, 0, 0,
                            onConfirm = { _, _, _, _, _ -> }, onDismiss = {})
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("grid-columns-dialog").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("phone-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(328f, bounds.width, 1f)
        assertEquals(16f, bounds.left - viewport.left, 1f)
        assertEquals(16f, viewport.right - bounds.right, 1f)
    }

    @Test
    fun longSelectionListKeepsCancelVisibleWhenWindowBecomesShort() {
        var viewport by mutableStateOf(DpSize(800.dp, 1100.dp))
        compose.setContent {
            val window = remember {
                object : WindowInfo {
                    override val isWindowFocused = true
                    override val containerSize: IntSize
                        get() = IntSize(viewport.width.value.toInt(), viewport.height.value.toInt())
                }
            }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalWindowInfo provides window,
                    LocalTabletLayoutEnabled provides true,
                    LocalMainNavController provides rememberNavController(),
                ) {
                    Box(Modifier.requiredSize(viewport.width, viewport.height)) {
                        SelectDialog(
                            visible = true,
                            title = "选择日期",
                            value = "1",
                            modifier = Modifier.widthIn(max = 420.dp),
                            selectOptionList = (1..100).map { SelectOption("选项 $it", "$it") },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.runOnIdle { viewport = DpSize(1100.dp, 400.dp) }
        compose.waitForIdle()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }
}
