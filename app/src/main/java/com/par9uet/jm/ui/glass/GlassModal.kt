package com.par9uet.jm.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.navigation.HierarchicalBackHandler

/**
 * 玻璃弹窗的默认几何。
 *
 * 手机：[MaxSurfaceWidth] 收口（常见手机宽屏普遍小于它，几乎不生效）。
 * 平板：弹窗拉伸到窗口宽度的 [TabletWidthFraction]，不再固定 480dp 收成窄条。
 * 平板宽度统一由公共层决定；调用方的手机宽度限制不会覆盖这个比例。
 */
object GlassModalDefaults {
    val MaxSurfaceWidth: Dp = 480.dp

    /** 平板上弹窗相对窗口宽度的比例。 */
    const val TabletWidthFraction: Float = 0.75f
}

/** 每次组合都按当前窗口计算，旋转与分屏不保留之前的宽度。 */
@Composable
internal fun tabletModalWidth(): Dp? {
    if (!LocalTabletLayoutEnabled.current) return null
    val widthPx = LocalWindowInfo.current.containerSize.width
    if (widthPx <= 0) return null
    return with(LocalDensity.current) { widthPx.toDp() } * GlassModalDefaults.TabletWidthFraction
}

/**
 * Real-glass modal rendered INSIDE the page's existing GlassCaptureHost overlay.
 *
 * The backdrop blurs live page content through the shared registry: the full-screen layer is
 * transparent but still handles outside taps, and [surface] becomes a normal GlassSurface, so
 * API 31+ gets true Gaussian backdrop blur and Android 11 gets the existing translucent
 * fallback. No extra capture host is created; callers must place this in CommonScaffold
 * overlayContent (or equivalent).
 *
 * Motion: enter fade ~200ms + scale 0.96->1; exit reverses and the full-screen hit layer is
 * removed once the transition finishes, so no invisible layer can intercept input. Outside tap
 * and Back dismissal are individually configurable.
 *
 * [visible] is the logical visibility CONTROLLED BY THE CALLER (callers keep this composable
 * composed and flip [visible]); [onDismissRequest] fires for outside tap / Back so the caller
 * can set [visible] = false, after which the exit animation actually runs to completion.
 */
@Composable
fun GlassModal(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceId: String = "glass-modal",
    dismissOnOutsideClick: Boolean = true,
    dismissOnBack: Boolean = true,
    alignment: Alignment = Alignment.Center,
    style: GlassSurfaceStyle = GlassSurfaceStyle(cornerRadius = 24.dp),
    surface: @Composable () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = visible
    val transition = rememberTransition(
        transitionState = visibleState,
        label = "glass-modal-transition",
    )
    val surfaceAlpha by transition.animateFloat(
        transitionSpec = { tween(200) },
        label = "glass-modal-surface-alpha",
    ) { if (it) 1f else 0f }
    val surfaceScale by transition.animateFloat(
        transitionSpec = { tween(200) },
        label = "glass-modal-surface-scale",
    ) { if (it) 1f else 0.96f }
    val active = transition.currentState || transition.isRunning
    val scrimInteraction = remember { MutableInteractionSource() }
    val surfaceInteraction = remember { MutableInteractionSource() }
    val tabletWidth = tabletModalWidth()
    val density = LocalDensity.current
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
    val topClearance = with(density) { safeInsets.getTop(this).toDp() } +
        AppGlassTopBarDefaults.ContentHeight + 16.dp
    val bottomClearance = with(density) { safeInsets.getBottom(this).toDp() } + 16.dp
    val leftClearance = with(density) { safeInsets.getLeft(this, androidx.compose.ui.unit.LayoutDirection.Ltr).toDp() } + 16.dp
    val rightClearance = with(density) { safeInsets.getRight(this, androidx.compose.ui.unit.LayoutDirection.Ltr).toDp() } + 16.dp
    if (dismissOnBack) {
        HierarchicalBackHandler(enabled = visible && active) {
            onDismissRequest()
        }
    }
    // 宽度来自当前窗口，不读 Box 约束；用 Box 避免无用 subcomposition。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (active) {
                    Modifier.clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = {
                            if (dismissOnOutsideClick) onDismissRequest()
                        },
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        // The full-window scrim still blocks the toolbar. Only the panel is constrained to
        // the safe viewport, so short landscape windows keep chrome and actions accessible.
        Box(Modifier.fillMaxSize().absolutePadding(left = leftClearance, right = rightClearance, top = topClearance, bottom = bottomClearance),
            contentAlignment = alignment) {
            // 先确定平板的精确宽度，再应用调用方修饰符，避免旧的 widthIn/width 改写 75%。
            val panelModifier = if (tabletWidth != null) {
                Modifier.width(tabletWidth).then(modifier)
            } else {
                modifier.widthIn(max = GlassModalDefaults.MaxSurfaceWidth)
            }
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(160)),
            ) {
                GlassSurface(
                    surfaceId = surfaceId,
                    modifier = panelModifier
                        .then(
                            if (dismissOnOutsideClick) {
                                Modifier.clickable(
                                    interactionSource = surfaceInteraction,
                                    indication = null,
                                    onClick = {},
                                )
                            } else {
                                Modifier
                            }
                        ),
                    style = style,
                    surfaceAlpha = surfaceAlpha,
                    surfaceScale = surfaceScale,
                ) {
                    surface()
                }
            }
        }
    }
}

/** Canonical glass confirmation layout used by destructive-action confirmations. */
@Composable
fun GlassConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceId: String = "glass-confirm-dialog",
    dismissText: String = "取消",
    destructive: Boolean = false,
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        // 手机保持原先 420dp 上限；平板交给 GlassModal 的整屏 3/4。
        modifier = modifier.then(
            if (LocalTabletLayoutEnabled.current) {
                Modifier
            } else {
                Modifier.widthIn(max = 420.dp)
            }
        ),
        surfaceId = surfaceId,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(dismissText)
                }
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.heightIn(min = 44.dp),
                    colors = if (destructive) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}
