package com.par9uet.jm.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import com.par9uet.jm.ui.models.CoverNavigator
import com.par9uet.jm.ui.models.LocalCoverNavigator
import com.par9uet.jm.ui.models.LocalCoverAnchors
import com.par9uet.jm.ui.models.LocalCoverReturnHost
import com.par9uet.jm.ui.interaction.CoverEnterMillis
import com.par9uet.jm.ui.interaction.DetailContentEnterMillis
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class CoverOrigin(val bounds: Rect?, val background: ImageBitmap?, val viewport: IntSize,
    val comicId: Int? = null, val sourcePageKey: String? = null)

/** 截图只在用户点击封面时产生；条目销毁即释放，不保存在 SavedState 或磁盘。 */
internal class CoverOrigins {
    var pending: Pair<Int, CoverOrigin>? = null
    private val entries = linkedMapOf<String, CoverOrigin>()
    fun take(entry: NavBackStackEntry, comicId: Int): CoverOrigin? {
        entries[entry.id]?.let { return it }
        val next = pending?.takeIf { it.first == comicId }?.second ?: return null
        pending = null
        // 限制嵌套详情的截图内存；较早条目退化为无来源动画。
        if (entries.size >= 3) entries.remove(entries.keys.first())
        entries[entry.id] = next
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    entries.remove(entry.id)
                    source.lifecycle.removeObserver(this)
                }
            }
        }
        entry.lifecycle.addObserver(observer)
        return next
    }
}

@Composable
internal fun CoverNavigationHost(origins: CoverOrigins, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var opening by remember { mutableStateOf(false) }
    var windowPosition by remember { mutableStateOf(Offset.Zero) }
    val nav = LocalMainNavController.current
    val anchors = remember { CoverReturnAnchors() }
    val returns = remember(scope, nav) { CoverReturns(scope, anchors, { nav.currentBackStackEntry?.id }, { windowPosition }) }
    val navigator = remember(origins, layer) {
        CoverNavigator { comic, bounds, open ->
            if (!opening) {
                opening = true
                val sourceEntry = nav.currentBackStackEntry
                scope.launch {
                    try {
                        // A rapid tap may land while this page is still entering. Capture its settled,
                        // current display list, rather than a previous frame / translated thumbnail.
                        sourceEntry?.lifecycle?.currentStateFlow?.first {
                            it == Lifecycle.State.RESUMED || it == Lifecycle.State.DESTROYED
                        }
                        if (sourceEntry != nav.currentBackStackEntry ||
                            sourceEntry?.lifecycle?.currentState == Lifecycle.State.DESTROYED) return@launch
                        withFrameNanos { }
                        withFrameNanos { }
                        val viewport = Rect(windowPosition, Size(size.width.toFloat(), size.height.toFloat()))
                        val currentBounds = anchors.source(comic.id, sourceEntry?.id, viewport,
                            bounds?.translate(windowPosition))?.boundsInWindow?.translate(-windowPosition) ?: bounds
                        val image = if (size.width > 0 && size.height > 0) {
                            try { layer.toImageBitmap() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        } else null
                        if (sourceEntry != nav.currentBackStackEntry) return@launch
                        origins.pending = comic.id to CoverOrigin(currentBounds, image, size, comic.id, sourceEntry?.id)
                        open()
                        delay((CoverEnterMillis + DetailContentEnterMillis).toLong())
                    } finally { opening = false }
                }
            }
        }
    }
    CompositionLocalProvider(LocalCoverNavigator provides navigator, LocalCoverAnchors provides anchors,
        LocalCoverReturnHost provides returns) {
        Box(Modifier.fillMaxSize().onSizeChanged {
            if (size != it) returns.finishOnResize()
            size = it
        }
            .onGloballyPositioned { windowPosition = it.positionInWindow() }) {
            // Capture only the page, in its own display list. The sibling return overlay must
            // not invalidate and re-record the entire navigation viewport on every frame.
            Box(Modifier.fillMaxSize().graphicsLayer().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }) { content() }
            CoverReturnOverlay(returns, androidx.compose.material3.MaterialTheme.colorScheme.background)
        }
    }
}
