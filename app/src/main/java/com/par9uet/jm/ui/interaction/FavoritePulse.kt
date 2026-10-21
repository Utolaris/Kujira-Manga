package com.par9uet.jm.ui.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 详情与阅读器共用一次心形放大/回弹；连续点击重新开始，不叠加动画。 */
internal class FavoritePulse(private val scope: CoroutineScope) {
    private val animation = Animatable(1f)
    private var job: Job? = null
    val scale: Float get() = animation.value

    fun trigger() {
        if (!motionEnabled()) return
        job?.cancel()
        job = scope.launch {
            animation.snapTo(1f)
            animation.animateTo(1.25f, tween(120))
            animation.animateTo(1f, tween(220, easing = Easing { fraction ->
                val t = fraction - 1f
                t * t * (3f * t + 2f) + 1f
            }))
        }
    }
}

@Composable
internal fun rememberFavoritePulse(): FavoritePulse {
    val scope = rememberCoroutineScope()
    return remember(scope) { FavoritePulse(scope) }
}
