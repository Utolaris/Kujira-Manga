package com.par9uet.jm.ui.interaction

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import com.par9uet.jm.ui.models.LocalDetailEntry

internal const val DetailReflowMillis = 320

/** Layout dimensions retarget from their current value; the page itself never becomes transparent. */
@Composable
internal fun animateDetailDimension(target: Dp, label: String): State<Dp> = animateDpAsState(
    targetValue = target,
    animationSpec = tween(
        if (motionEnabled() && LocalDetailEntry.current?.coverInFlight != true) DetailReflowMillis else 0,
        easing = CoverEasing,
    ),
    label = label,
)
