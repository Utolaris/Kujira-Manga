package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.AllGridColumnSliderDialog
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.AppGlassTopBar
import org.junit.Rule
import org.junit.Test

class GridColumnSliderDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun gridColumnsUseDiscreteStopsAndTheDownloadCenterLabelAndOnlySaveOnConfirm() {
        var saved: List<Int>? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) {
                    GlassCaptureHost(Modifier.fillMaxSize(), sourceContent = { Box(Modifier.fillMaxSize()) }, overlayContent = {
                        AppGlassTopBar(surfaceId = "settings-bar", statusBarInset = 0.dp,
                            title = { Text("设置标题") })
                        AllGridColumnSliderDialog(true, 0, 0, 0, 0, 0,
                            onConfirm = { home, collect, download, history, search -> saved = listOf(home, collect, download, history, search) },
                            onDismiss = {})
                    })
                }
            }
        }
        val toolbar = compose.onNodeWithText("设置标题").fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNodeWithTag("grid-columns-dialog").fetchSemanticsNode().boundsInRoot
        assertTrue("Modal must stay below the sharp toolbar text", dialog.top >= toolbar.bottom)
        compose.onNodeWithText("下载中心").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("缓存").assertDoesNotExist()
        compose.onNodeWithContentDescription("首页网格列数").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithText("2 列").assertExists()
        compose.onNodeWithContentDescription("下载中心网格列数").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.onNodeWithText("6 列").assertExists()
        compose.runOnIdle { assertEquals(null, saved) }
        compose.onNodeWithText("确定").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(2, 0, 6, 0, 0), saved) }
    }
}
