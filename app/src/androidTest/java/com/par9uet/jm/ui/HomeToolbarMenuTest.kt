package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassAnchoredMenuState
import com.par9uet.jm.ui.glass.rememberGlassAnchoredMenuState
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.HomeGlassTopBar
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeToolbarMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tappingHomeTitleOpensCategoriesAndUnreadBellStateUpdates() {
        lateinit var categories: GlassAnchoredMenuState
        var unread by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMainNavController provides rememberNavController(),
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) {
                    val categoryMenu = rememberGlassAnchoredMenuState()
                    categories = categoryMenu
                    val moreMenu = rememberGlassAnchoredMenuState()
                    GlassCaptureHost(Modifier.fillMaxSize(), sourceContent = {}, overlayContent = {
                        HomeGlassTopBar("本周热门", 0.dp, categoryMenu, moreMenu,
                            onSearch = {}, onDownload = {}, onNotifications = {}, hasUnreadNotifications = unread)
                    })
                }
            }
        }
        compose.onNodeWithContentDescription("通知").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "无未读通知"))
        compose.onNodeWithText("本周热门").performClick()
        compose.runOnIdle {
            assertTrue(categories.expanded)
            unread = true
        }
        compose.onNodeWithContentDescription("通知").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "有未读通知"))
    }
}
