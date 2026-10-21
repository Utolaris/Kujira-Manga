package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.models.LocalDetailEntry
import com.par9uet.jm.ui.models.CoverAnchor
import com.par9uet.jm.ui.models.LocalCoverAnchors
import com.par9uet.jm.ui.models.LocalCoverPageKey
import androidx.compose.runtime.remember

/** Register only real, currently composed covers; disposed or clipped items cannot be return targets. */
@Composable
internal fun Modifier.coverSourceBounds(comicId: Int, onBounds: (Rect) -> Unit): Modifier {
    val anchors = LocalCoverAnchors.current
    val pageKey = LocalCoverPageKey.current ?: (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    val key = remember(comicId, pageKey) { Any() }
    DisposableEffect(anchors, key) { onDispose { anchors?.remove(key) } }
    return onGloballyPositioned {
        onBounds(it.coverBoundsInRoot())
        val full = Rect(it.localToWindow(Offset.Zero), it.localToWindow(Offset(it.size.width.toFloat(), it.size.height.toFloat())))
        anchors?.update(CoverAnchor(key, pageKey, comicId, full, it.boundsInWindow()))
    }.drawWithContent { if (anchors?.hidden(key) != true) drawContent() }
}

/** Scroll clipping must not turn a partially visible cover into a differently shaped thumbnail. */
internal fun LayoutCoordinates.coverBoundsInRoot(): Rect = Rect(
    localToRoot(Offset.Zero),
    localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
)

/** Record the real destination cover once per invalidation, then draw it above the page reveal. */
@Composable
internal fun Modifier.detailEntryCover(ready: Boolean = true): Modifier {
    val entry = LocalDetailEntry.current ?: return this
    val layer = rememberGraphicsLayer()
    SideEffect { entry.coverReady = ready }
    DisposableEffect(entry, layer) { onDispose { entry.unregisterCover(layer) } }
    return onGloballyPositioned { coordinates ->
        val position = coordinates.localToWindow(Offset.Zero)
        entry.registerCover(Rect(position, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())),
            layer) { if (coordinates.isAttached) coordinates.boundsInWindow() else null }
    }.drawWithContent {
        layer.record {
            this@drawWithContent.drawContent()
            // The fallback belongs to the actual cover layout: scrolling, clipping and Glass blur
            // keep working even when the full-resolution destination request is slow or fails.
            if (!ready) entry.sourceCover?.let { (bitmap, source) ->
                drawCoverBitmap(bitmap, Rect(Offset.Zero, size), source = source)
            }
        }
        if (!entry.coverInFlight) drawLayer(layer)
    }
}

/** Float transforms avoid rounding the cover to integer pixels on every slow animation frame. */
internal fun DrawScope.drawCoverBitmap(bitmap: ImageBitmap, bounds: Rect, alpha: Float = 1f,
    source: Rect = Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())) {
    if (bounds.width <= 0f || bounds.height <= 0f || source.width <= 0f || source.height <= 0f) return
    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
        translate(bounds.left, bounds.top) {
            scale(bounds.width / source.width, bounds.height / source.height, Offset.Zero) {
                drawImage(bitmap, topLeft = -source.topLeft, alpha = alpha)
            }
        }
    }
}

/** Keep layout intact while the introduction and the tags/comments enter on their own layers. */
@Composable
internal fun Modifier.detailEntryContent(horizontal: Boolean = false): Modifier {
    val entry = LocalDetailEntry.current ?: return this
    val distance = with(LocalDensity.current) { 12.dp.toPx() }
    return graphicsLayer {
        alpha = entry.contentProgress
        translationX = if (horizontal) distance * (1f - entry.contentProgress) else 0f
        translationY = if (horizontal) 0f else distance * (1f - entry.contentProgress)
    }
}

internal class DetailRevealShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(0f, 0f, size.width, size.height * progress.coerceIn(0f, 1f)))
}
