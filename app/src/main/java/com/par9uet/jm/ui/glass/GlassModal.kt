package com.par9uet.jm.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * 玻璃弹窗的默认几何。
 *
 * 手机：[MaxSurfaceWidth] 收口（常见手机宽屏普遍小于它，几乎不生效）。
 * 平板：弹窗拉伸到窗口宽度的 [TabletWidthFraction]，不再固定 480dp 收成窄条。
 * 需要更窄或更宽的弹窗自己传 `modifier` 覆盖。
 */
object GlassModalDefaults {
    val MaxSurfaceWidth: Dp = 480.dp

    /** 平板上弹窗相对窗口宽度的比例。 */
    const val TabletWidthFraction: Float = 0.75f
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
    val isTabletLayout = LocalTabletLayoutEnabled.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    if (dismissOnBack) {
        BackHandler(enabled = visible && active) {
            onDismissRequest()
        }
    }
    BoxWithConstraints(
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
        contentAlignment = alignment,
    ) {
        // 平板：按整屏宽的 3/4（悬浮导航不占布局位，仍用窗口宽）；
        // 手机：固定收口，避免大对话框。
        val defaultMaxWidth = if (isTabletLayout && windowWidthPx > 0) {
            with(density) { windowWidthPx.toDp() } * GlassModalDefaults.TabletWidthFraction
        } else {
            GlassModalDefaults.MaxSurfaceWidth
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160)),
        ) {
            GlassSurface(
                surfaceId = surfaceId,
                modifier = modifier
                    .widthIn(max = defaultMaxWidth)
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
                style = GlassSurfaceStyle(cornerRadius = 24.dp),
                surfaceAlpha = surfaceAlpha,
                surfaceScale = surfaceScale,
            ) {
                surface()
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
