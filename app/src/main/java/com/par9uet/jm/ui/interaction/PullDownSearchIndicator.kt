package com.par9uet.jm.ui.interaction

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle

/**
 * The one spec shared by the pill's grow and its retract, so entering and leaving the pull state
 * look like the same motion played in opposite directions.
 */
private val PullDownSearchRevealSpec = tween<Float>(
    durationMillis = 90,
    easing = FastOutSlowInEasing,
)

/** Reveal fraction at which the pill reaches full opacity; before that the alpha ramps with it. */
private const val PullDownSearchAlphaSaturation = 1.35f

@Composable
internal fun PullDownSearchIndicator(
    state: PullDownActionState,
    surfaceId: String,
    topOffset: Dp,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val targetProgress = when (phase) {
        PullDownActionPhase.ARMED,
        PullDownActionPhase.TRIGGERING -> 1f
        PullDownActionPhase.IDLE,
        PullDownActionPhase.PULLING -> state.progress
    }
    // Size, position and alpha all read this single animated value. Driving the geometry straight
    // off the live pull progress used to snap the pill back to its resting size and offset in one
    // frame whenever the gesture was abandoned (release below threshold, or the list regaining
    // scroll while a pull was in flight), while the separately animated alpha kept fading over the
    // next frames -- a hard cut followed by a ghost. One source, one spec: no desync is possible
    // and the exit mirrors the entrance.
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = PullDownSearchRevealSpec,
        label = "$surfaceId-reveal",
    )
    val width = 116.dp + 28.dp * progress
    val translation = (-12).dp + 18.dp * progress
    val alpha = (progress * PullDownSearchAlphaSaturation).coerceIn(0f, 1f)
    val emphasized = phase == PullDownActionPhase.ARMED ||
        phase == PullDownActionPhase.TRIGGERING
    val label = when (phase) {
        PullDownActionPhase.IDLE,
        PullDownActionPhase.PULLING -> "下拉搜索"
        PullDownActionPhase.ARMED -> "松开搜索"
        PullDownActionPhase.TRIGGERING -> "进入搜索"
    }

    GlassSurface(
        surfaceId = surfaceId,
        modifier = modifier
            .offset(y = topOffset + translation)
            .width(width)
            .height(40.dp),
        style = GlassSurfaceStyle(cornerRadius = 20.dp),
        surfaceAlpha = alpha,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
                label = "$surfaceId-label",
            ) { text ->
                Text(
                    text = text,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
