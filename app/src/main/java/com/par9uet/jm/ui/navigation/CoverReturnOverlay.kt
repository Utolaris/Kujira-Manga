package com.par9uet.jm.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.par9uet.jm.ui.interaction.CoverReturnPath
import com.par9uet.jm.ui.interaction.coverCenterExitBounds
import com.par9uet.jm.ui.interaction.coverEntryBounds
import com.par9uet.jm.ui.interaction.motionEnabled
import com.par9uet.jm.ui.interaction.coverExitDuration
import com.par9uet.jm.ui.interaction.drawCoverBitmap
import com.par9uet.jm.ui.models.CoverAnchor
import com.par9uet.jm.ui.models.CoverAnchorRegistry
import com.par9uet.jm.ui.models.CoverReturnFrame
import com.par9uet.jm.ui.models.CoverReturnHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class CoverReturnAnchors : CoverAnchorRegistry {
    private val anchors = HashMap<Any, () -> CoverAnchor?>()
    var hiddenKey by mutableStateOf<Any?>(null)
    var pending by mutableStateOf<Pair<String, Int>?>(null)
    override fun update(anchor: CoverAnchor) { track(anchor.key) { anchor } }
    override fun track(key: Any, source: () -> CoverAnchor?) { anchors[key] = source }
    override fun remove(key: Any) { anchors.remove(key) }
    override fun hidden(key: Any): Boolean = hiddenKey === key || pending?.let { (page, comic) ->
        anchors[key]?.invoke()?.let { it.pageKey == page && it.comicId == comic }
    } == true

    fun visible(key: Any, pageKey: String?, viewport: Rect): CoverAnchor? = anchors[key]?.invoke()?.takeIf {
        it.pageKey == pageKey && it.boundsInWindow.width > 0f && it.boundsInWindow.height > 0f &&
            it.visibleBoundsInWindow.intersect(viewport).let { rect -> rect.width > 0f && rect.height > 0f }
    }

    fun source(comicId: Int, pageKey: String?, viewport: Rect, near: Rect?): CoverAnchor? =
        anchors.values.asSequence().mapNotNull { it() }
            .filter { it.comicId == comicId && visible(it.key, pageKey, viewport) != null }
            .minByOrNull { (it.boundsInWindow.center - (near?.center ?: viewport.center)).getDistanceSquared() }

    fun anyVisible(pageKey: String?, viewport: Rect) = anchors.keys.any { visible(it, pageKey, viewport) != null }

    fun find(frame: CoverReturnFrame, pageKey: String?, viewport: Rect): CoverAnchor? {
        if (pageKey == null || pageKey != frame.sourcePageKey || frame.comicId == null) return null
        val near = frame.sourceBounds?.center?.plus(viewport.topLeft) ?: viewport.center
        return anchors.values.asSequence().mapNotNull { it() }
            .filter { it.comicId == frame.comicId && visible(it.key, pageKey, viewport) != null }
            .minByOrNull { (it.boundsInWindow.center - near).getDistanceSquared() }
    }
}

/** Lives above NavHost, so the previous page can lay out before choosing the return destination. */
internal class CoverReturns(
    private val scope: CoroutineScope, val anchors: CoverReturnAnchors,
    private val currentPageKey: () -> String?, private val position: () -> Offset,
) : CoverReturnHost {
    private var animation: kotlinx.coroutines.Job? = null

    fun finishOnResize() {
        animation?.cancel()
        frame = null
        anchors.hiddenKey = null
        anchors.pending = null
    }
    var frame by mutableStateOf<CoverReturnFrame?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var coverBounds by mutableStateOf<Rect?>(null)
        private set
    var coverAlpha by mutableFloatStateOf(1f)
        private set

    private suspend fun awaitVisibleAnchor(frame: CoverReturnFrame, viewport: Rect): CoverAnchor? {
        while (true) {
            val page = currentPageKey()
            val anchor = anchors.find(frame, page, viewport)
            if (anchor != null || anchors.anyVisible(page, viewport)) return anchor
            // Coordinates are deliberately not snapshot state. Observe layout only while an
            // actual return is waiting for its destination, never during ordinary scrolling.
            withFrameNanos { }
        }
    }

    override fun close(frame: CoverReturnFrame, after: () -> Unit) {
        if (this.frame != null) return
        this.frame = frame
        val flying = frame.cover != null && frame.coverBounds != null
        coverBounds = frame.coverBounds.takeIf { flying }
        coverAlpha = 1f
        progress = 0f
        anchors.pending = if (flying && frame.sourcePageKey != null && frame.comicId != null)
            frame.sourcePageKey to frame.comicId else null
        animation = scope.launch {
            try {
                after()
                // Let restored scroll position, filters and favorite removals reach actual layout.
                withFrameNanos { }
                withFrameNanos { }
                val viewport = Rect(position(), Size(frame.viewport.width.toFloat(), frame.viewport.height.toFloat()))
                var anchor = if (flying) withTimeoutOrNull(160) {
                    awaitVisibleAnchor(frame, viewport)
                } else null
                anchors.hiddenKey = anchor?.key
                anchors.pending = null
                val start = coverBounds
                val size = viewport.size
                var target = anchor?.boundsInWindow?.translate(-viewport.topLeft)
                    ?: start?.let { coverCenterExitBounds(it, size) }
                val duration = coverExitDuration(frame.reveal, start, target, fading = anchor == null)
                val path = CoverReturnPath(start, target, fading = anchor == null)
                Animatable(0f).animateTo(1f, tween(if (motionEnabled()) duration else 0, easing = LinearEasing)) {
                    // A late refresh can remove the destination while closing. Retarget from the
                    // current rectangle, rather than jumping to a stale slot or another comic.
                    val currentAnchor = anchor?.let { anchors.visible(it.key, currentPageKey(), viewport) }
                    if (anchor != null && currentAnchor == null) {
                        target = path.bounds?.let { coverCenterExitBounds(it, size) }
                        anchor = null
                        anchors.hiddenKey = null
                    } else if (currentAnchor != null && currentAnchor.boundsInWindow.translate(-viewport.topLeft) != target) {
                        target = currentAnchor.boundsInWindow.translate(-viewport.topLeft)
                    }
                    path.advance(value, target, fadeOut = anchor == null)
                    coverBounds = path.bounds
                    coverAlpha = path.alpha
                    progress = value
                }
            } finally {
                if (this@CoverReturns.frame === frame) {
                    this@CoverReturns.frame = null
                    anchors.hiddenKey = null
                    anchors.pending = null
                }
            }
        }
    }
}

@Composable
internal fun CoverReturnOverlay(returns: CoverReturns, backgroundColor: Color) {
    val frame = returns.frame ?: return
    // Pointer consumption cannot stop Android's edge-back dispatcher. While the committed
    // return owns the screen, drain additional back gestures before the restored NavHost.
    HierarchicalBackHandler { }
    Canvas(Modifier.fillMaxSize().testTag("cover-return-overlay")) {
        val f = returns.progress
        drawRect(Color.Black.copy(alpha = frame.dim * (1f - f)))
        val rect = coverEntryBounds(frame.backgroundBounds, Rect(Offset.Zero, size), f)
        clipRect(rect.left, rect.top, rect.right, rect.top + rect.height * frame.reveal * (1f - f)) {
            if (frame.background != null) drawCoverBitmap(frame.background, rect)
            else drawRect(backgroundColor, rect.topLeft, rect.size)
        }
        returns.coverBounds?.let { bounds -> frame.cover?.let { drawCoverBitmap(it, bounds, returns.coverAlpha) } }
    }
    Box(Modifier.fillMaxSize().pointerInput(returns) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    })
}
