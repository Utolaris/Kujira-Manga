package com.par9uet.jm.ui.interaction

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

internal const val PressScale = .96f
internal const val PressInMillis = 120
internal const val PressOutMillis = 200

internal fun motionEnabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

/** Foundation clickable/combinedClickable 保留原涟漪，统一增加绘制缩放，不修改布局或热区。 */
internal class PressFeedbackIndication(private val ripple: IndicationNodeFactory) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node =
        object : DelegatingNode(), DrawModifierNode {
            private val scale = Animatable(1f)
            init { delegate(ripple.create(interactionSource)) }
            override fun onAttach() {
                coroutineScope.launch {
                    scale.snapTo(1f)
                    interactionSource.interactions.filter {
                        it is PressInteraction.Press || it is PressInteraction.Release || it is PressInteraction.Cancel
                    }.collectLatest { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> if (motionEnabled()) scale.animateTo(PressScale, tween(PressInMillis)) else scale.snapTo(1f)
                            is PressInteraction.Release, is PressInteraction.Cancel ->
                                if (motionEnabled()) scale.animateTo(1f, tween(PressOutMillis)) else scale.snapTo(1f)
                        }
                    }
                }
            }
            override fun ContentDrawScope.draw() {
                scale(scale.value) { this@draw.drawContent() }
            }
        }
    override fun equals(other: Any?) = other is PressFeedbackIndication && ripple == other.ripple
    override fun hashCode() = ripple.hashCode()
}

/** Material 组件自行创建 ripple，因此由公共适配组件共享同一个 InteractionSource。 */
@Composable
internal fun Modifier.pressFeedback(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && motionEnabled()) PressScale else 1f,
        animationSpec = tween(if (pressed) PressInMillis else PressOutMillis), label = "press-feedback",
    )
    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}
