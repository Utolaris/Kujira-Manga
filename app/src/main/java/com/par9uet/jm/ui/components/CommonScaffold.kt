package com.par9uet.jm.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.AppGlassTopBarDefaults
import com.par9uet.jm.ui.glass.GlassCaptureHost

/** Shared glass scaffold for hierarchical destinations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommonScaffold(
    title: String,
    titleContent: @Composable (() -> Unit)? = null,
    titleTopPadding: Dp = 0.dp,
    onNavigateBack: (() -> Unit)? = null,
    navigationContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    overlayContent: @Composable BoxScope.() -> Unit = {},
    variableTopBar: (@Composable (statusBarInset: Dp) -> Unit)? = null,
    content: @Composable (topContentPadding: Dp, bottomContentPadding: Dp) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    // 阅读器退出时状态栏/导航栏可能仍处于隐藏态；首帧就按 ignoringVisibility 保留完整安全区，
    // 避免返回瞬间顶栏和封面从顶部向下位移造成卡顿感（与 ComicDetailScreen 一致）。
    val statusBarInset = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp() }
    val navigationBarInset = with(density) { WindowInsets.navigationBarsIgnoringVisibility.getBottom(this).toDp() }
    val topContentPadding = statusBarInset + AppGlassTopBarDefaults.ContentHeight

    GlassCaptureHost(
        modifier = Modifier.fillMaxSize(),
        sourceContent = {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(),
                bottomBar = bottomBar,
            ) { innerPadding ->
                // The source viewport is intentionally FULL-SCREEN: scrollable children
                // extend underneath the glass top bar so the blur samples live content,
                // exactly like Home. Children receive the chrome insets and apply them as
                // their own scroll contentPadding instead of being clipped below the bar.
                Box(modifier = Modifier.fillMaxSize()) {
                    content(
                        topContentPadding,
                        maxOf(innerPadding.calculateBottomPadding(), navigationBarInset),
                    )
                }
            }
        },
        overlayContent = {
            Box(modifier = Modifier.fillMaxSize()) {
                if (variableTopBar != null) {
                    variableTopBar(statusBarInset)
                } else {
                    AppGlassTopBar(
                        surfaceId = "common-top-bar",
                        statusBarInset = statusBarInset,
                        modifier = Modifier.align(Alignment.TopCenter),
                        navigationIcon = {
                            if (navigationContent != null) {
                                navigationContent()
                            } else {
                                BackIconButton(onClick = onNavigateBack)
                            }
                        },
                        title = {
                            Column(
                                modifier = Modifier.padding(top = titleTopPadding),
                            ) {
                                if (titleContent != null) {
                                    titleContent()
                                } else {
                                    Text(
                                        text = title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        },
                        actions = actions,
                    )
                }
                overlayContent()
            }
        },
    )
}
