package com.par9uet.jm.ui.interaction

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity

/** One tick per visible collision; a released effect can produce a new collision at the same edge. */
internal class ScrollEdgeTracker {
    private var edge = 0
    fun collision(remaining: Float, visible: Boolean, userDriven: Boolean): Boolean {
        if (!visible) { edge = 0; return false }
        if (!userDriven) return false
        val next = when { remaining > .5f -> 1; remaining < -.5f -> -1; else -> 0 }
        if (next == 0 || next == edge) return false
        edge = next
        return true
    }
    fun released() { edge = 0 }
}

/** Attach to the real platform effect, before overscroll/nested-scroll parents consume the remainder. */
internal class HapticOverscrollEffect(
    private val delegate: OverscrollEffect,
    private val feedback: () -> Unit,
) : OverscrollEffect {
    private val tracker = ScrollEdgeTracker()
    private var userCycle = false
    override val node get() = delegate.node
    override val isInProgress get() = delegate.isInProgress

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (!delegate.isInProgress) tracker.released()
        if (source == NestedScrollSource.UserInput) userCycle = true
        var remaining = Offset.Zero
        val result = delegate.applyToScroll(delta, source) { available ->
            performScroll(available).also { remaining = available - it }
        }
        if (tracker.collision(remaining.y, delegate.isInProgress || node.node.isAttached,
                source == NestedScrollSource.UserInput)) feedback()
        return result
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        var remaining = Velocity.Zero
        try {
            delegate.applyToFling(velocity) { available ->
                performFling(available).also { remaining = available - it }
            }
            // onAbsorb can begin drawing on the next frame, before isInProgress reports distance.
            if (tracker.collision(remaining.y, delegate.isInProgress || node.node.isAttached, userCycle)) feedback()
        } finally { userCycle = false }
    }
}

private data class HapticOverscrollFactory(
    val delegate: OverscrollFactory, val feedback: () -> Unit,
) : OverscrollFactory {
    override fun createOverscrollEffect() = HapticOverscrollEffect(delegate.createOverscrollEffect(), feedback)
}

/** The factory is also forwarded explicitly through Glass's independent ComposeViews. */
@Composable
internal fun ProvideScrollEdgeFeedback(feedback: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val platform = LocalOverscrollFactory.current
    val view = LocalView.current
    val latest by rememberUpdatedState(feedback)
    val factory = remember(platform, view) {
        if (platform == null || platform is HapticOverscrollFactory) platform else HapticOverscrollFactory(platform) {
            if (latest != null) latest?.invoke() else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    CompositionLocalProvider(LocalOverscrollFactory provides factory, content = content)
}
