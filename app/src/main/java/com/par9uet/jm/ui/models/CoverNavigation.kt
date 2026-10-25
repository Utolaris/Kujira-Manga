package com.par9uet.jm.ui.models

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.par9uet.jm.data.models.Comic

/** 封面入口传递真实屏幕矩形；组合根负责预置漫画、截图及导航。 */
fun interface CoverNavigator {
    fun open(comic: Comic, bounds: Rect?, open: () -> Unit)
}
val LocalCoverNavigator = staticCompositionLocalOf<CoverNavigator?> { null }

interface DetailMotionControl {
    var dragBlocked: Boolean
    fun dismiss(after: () -> Unit)
}
val LocalDetailMotion = staticCompositionLocalOf<DetailMotionControl?> { null }

/** 详情内各绘制层共享转场时钟；封面坐标使用 window，跨 Glass 的 ComposeView 仍一致。 */
interface DetailEntryControl {
    val coverProgress: Float
    val contentProgress: Float
    val sourceCover: Pair<ImageBitmap, Rect>?
    var coverReady: Boolean
    val coverInFlight: Boolean
    fun registerCover(boundsInWindow: Rect, layer: GraphicsLayer, visibleBoundsInWindow: () -> Rect? = { boundsInWindow })
    fun unregisterCover(layer: GraphicsLayer)
}
val LocalDetailEntry = staticCompositionLocalOf<DetailEntryControl?> { null }

data class CoverAnchor(
    val key: Any, val pageKey: String?, val comicId: Int,
    val boundsInWindow: Rect, val visibleBoundsInWindow: Rect,
)

interface CoverAnchorRegistry {
    fun update(anchor: CoverAnchor)
    fun track(key: Any, source: () -> CoverAnchor?)
    fun remove(key: Any)
    fun hidden(key: Any): Boolean
}
val LocalCoverAnchors = staticCompositionLocalOf<CoverAnchorRegistry?> { null }
val LocalCoverPageKey = staticCompositionLocalOf<String?> { null }

/** Bitmaps detach the closing layers from the popped destination's composition lifecycle. */
data class CoverReturnFrame(
    val background: ImageBitmap?, val cover: ImageBitmap?,
    val backgroundBounds: Rect, val coverBounds: Rect?,
    val reveal: Float, val dim: Float, val viewport: IntSize,
    val comicId: Int?, val sourcePageKey: String?,
    val sourceBounds: Rect? = null,
)
fun interface CoverReturnHost {
    fun close(frame: CoverReturnFrame, after: () -> Unit)
}
val LocalCoverReturnHost = staticCompositionLocalOf<CoverReturnHost?> { null }
