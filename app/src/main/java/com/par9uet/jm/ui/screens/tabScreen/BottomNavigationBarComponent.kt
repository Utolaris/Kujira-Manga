package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.par9uet.jm.R
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.glass.GlassStyle
import com.par9uet.jm.ui.navigation.MainTab

@Composable
fun BottomNavigationBarComponent(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    navigationBarInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    PrimaryGlassBottomBar(
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
        navigationBarInset = navigationBarInset,
    )
}

@Composable
fun PrimaryGlassBottomBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Default,
    navigationBarInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // The bottom bar must only own the region it needs. A full-screen modifier here would
    // create an invisible hit-test layer above page content and modal dialogs, blocking taps.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(style.barHeight + style.outerMargin + navigationBarInset)
            .wrapContentHeight(Alignment.Bottom),
    ) {
        val barWidth = minOf(
            (maxWidth - style.outerMargin * 2).coerceAtLeast(0.dp),
            style.maxBarWidth,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = navigationBarInset + style.outerMargin),
        ) {
            GlassSurface(
                surfaceId = "primary-bottom-navigation",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(barWidth)
                    .height(style.barHeight),
                style = GlassSurfaceStyle(
                    cornerRadius = style.cornerRadius,
                    material = style.material,
                ),
            ) {
                val itemWidth = barWidth / MainTab.ordered.size
                val selectedIndex by animateFloatAsState(
                    targetValue = selectedTab.index.toFloat(),
                    animationSpec = tween(
                        durationMillis = 320,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "glass-selected-tab",
                )

                Box(
                    modifier = Modifier
                        .offset { IntOffset(x = (itemWidth * selectedIndex).roundToPx(), y = 0) }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(style.cornerRadius))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = style.selectedIndicatorAlpha,
                            ),
                        ),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    MainTab.ordered.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(style.cornerRadius))
                                .clickable(
                                    role = Role.Tab,
                                    onClick = { onTabSelected(tab) },
                                )
                                .semantics(mergeDescendants = true) {
                                    contentDescription = tab.navigationLabel
                                    selected = isSelected
                                    role = Role.Tab
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            MainTabIcon(
                                tab = tab,
                                contentDescription = null,
                                tint = contentColor,
                            )
                            Text(
                                text = tab.navigationLabel,
                                color = contentColor,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 平板左侧导航栏几何：玻璃条与占位宽度。 */
internal object TabletNavigationRailDefaults {
    val width = 72.dp
    val outerMargin = 8.dp
}

/**
 * 平板侧栏：与手机底栏同一套高斯模糊玻璃表面，条目垂直居中。
 * 必须画在 `GlassCaptureHost` 的 overlay 内，否则会退化成纯色。
 */
@Composable
fun GlassNavigationRailComponent(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        surfaceId = "primary-navigation-rail",
        modifier = modifier
            .width(TabletNavigationRailDefaults.width)
            .fillMaxHeight()
            .padding(vertical = TabletNavigationRailDefaults.outerMargin),
        style = GlassSurfaceStyle(cornerRadius = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MainTab.ordered.forEach { tab ->
                val isSelected = tab == selectedTab
                val contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            role = Role.Tab,
                            onClick = { onTabSelected(tab) },
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = tab.navigationLabel
                            selected = isSelected
                            role = Role.Tab
                        }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            MainTabIcon(
                                tab = tab,
                                contentDescription = null,
                                tint = contentColor,
                            )
                        }
                    } else {
                        MainTabIcon(
                            tab = tab,
                            contentDescription = null,
                            tint = contentColor,
                        )
                    }
                    Text(
                        text = tab.navigationLabel,
                        color = contentColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainTabIcon(
    tab: MainTab,
    contentDescription: String? = tab.navigationLabel,
    tint: Color? = null,
) {
    val resolvedTint = tint ?: LocalContentColor.current
    when (tab) {
        MainTab.Home -> Icon(
            painter = painterResource(R.drawable.home_icon),
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
        MainTab.Collect -> Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
        MainTab.Settings -> Icon(
            painter = painterResource(R.drawable.person_icon),
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
    }
}
