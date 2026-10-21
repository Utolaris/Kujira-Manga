package com.par9uet.jm.ui.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntSize
import com.par9uet.jm.ui.models.DetailEntryControl
import com.par9uet.jm.ui.models.LocalDetailEntry
import com.par9uet.jm.ui.models.DetailMotionControl
import com.par9uet.jm.ui.models.LocalDetailMotion
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.models.LocalCoverReturnHost
import com.par9uet.jm.ui.models.CoverReturnFrame
import com.par9uet.jm.ui.navigation.CoverOrigin
import com.par9uet.jm.ui.navigation.HierarchicalBackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sign

internal data class ReturnLayers(val background: ImageBitmap?, val cover: ImageBitmap?)

internal class DetailMotion(
    private val scope: CoroutineScope, private val height: Float,
    private val density: Float, entered: Boolean,
    private val viewport: () -> Rect,
    private val prepare: suspend (DetailMotion) -> ReturnLayers,
    private val close: (DetailMotion, ReturnLayers, () -> Unit) -> Unit,
) : DetailMotionControl, DetailEntryControl {
    var pose by mutableStateOf(CoverPose())
        private set
    override var coverProgress by mutableFloatStateOf(if (entered) 1f else 0f)
        private set
    override var contentProgress by mutableFloatStateOf(if (entered) 1f else 0f)
        private set
    override var sourceCover: Pair<ImageBitmap, Rect>? = null
    override var coverReady by mutableStateOf(false)
    override val coverInFlight by derivedStateOf { coverProgress < 1f || exiting }
    var cover by mutableStateOf<Pair<Rect, GraphicsLayer>?>(null)
        private set
    private var visibleCover: (() -> Rect?)? = null
    var returnCoverVisible by mutableStateOf(true)
        private set
    val blocksInput by derivedStateOf { busy || exiting || contentProgress < 1f }
    override fun registerCover(boundsInWindow: Rect, layer: GraphicsLayer, visibleBoundsInWindow: () -> Rect?) {
        if (boundsInWindow.width > 0f && boundsInWindow.height > 0f) {
            // Keep the untransformed layout rectangle while the whole page follows a drag.
            val rect = if (pose == CoverPose() || cover == null) boundsInWindow else cover!!.first
            cover = rect to layer
            visibleCover = visibleBoundsInWindow
        }
    }
    override fun unregisterCover(layer: GraphicsLayer) {
        if (cover?.second === layer) { cover = null; visibleCover = null }
    }
    private var blockDrag by mutableStateOf(false)
    override var dragBlocked: Boolean
        get() = blockDrag
        set(value) {
            blockDrag = value
            if (value) cancelDrag()
        }
    var dragging by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var exiting by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var pendingExit: (() -> Unit)? = null
    private var offset = Offset.Zero
    private var direction = 0f

    private fun animate(to: CoverPose, duration: Int, finish: () -> Unit = {}) {
        animation?.cancel()
        val start = pose
        busy = true
        animation = scope.launch {
            val fraction = Animatable(0f)
            fraction.animateTo(1f, tween(if (motionEnabled()) duration else 0, easing = CoverEasing)) {
                pose = start.interpolate(to, value)
            }
            pose = to
            busy = false
            finish()
        }
    }
    fun enter() {
        busy = true
        animation = scope.launch {
            // Glass owns a separate ComposeView. Give its first layout a bounded opportunity;
            // errors or missing data must never leave the page waiting for a cover forever.
            withTimeoutOrNull(160) { snapshotFlow { cover }.first { it != null } }
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(if (motionEnabled()) CoverEnterMillis else 0, easing = CoverEasing)) {
                coverProgress = value
            }
            coverProgress = 1f
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (motionEnabled()) DetailContentEnterMillis else 0, easing = CoverEasing)) {
                contentProgress = value
            }
            contentProgress = 1f
            busy = false
        }
    }
    fun beginDrag(): Boolean {
        if (dragBlocked || busy || exiting) return false
        dragging = true
        offset = Offset.Zero
        direction = 0f
        return true
    }
    fun drag(delta: Offset) {
        if (!dragging) return
        if (direction == 0f && delta.y != 0f) direction = sign(delta.y)
        offset += delta
        pose = coverDragPose(offset, height, direction)
    }
    fun release(velocityY: Float, onExit: () -> Unit) {
        if (!dragging) return
        dragging = false
        if (!dragBlocked && offset.y * direction > 0f && shouldDismissCover(offset.y, velocityY, height, density)) dismiss(onExit)
        else animate(CoverPose(), CoverReturnMillis)
    }
    fun cancelDrag() {
        if (!dragging) return
        dragging = false
        animate(CoverPose(), CoverReturnMillis)
    }
    override fun dismiss(after: () -> Unit) {
        if (exiting) return
        pendingExit = after
        returnCoverVisible = coverHasVisiblePixels(visibleCover?.invoke(), viewport())
        exiting = true
        dragging = false
        animation?.cancel()
        busy = true
        animation = scope.launch {
            val start = contentProgress
            if (start > 0f) {
                Animatable(start).animateTo(0f, tween(if (motionEnabled()) (DetailContentExitMillis * start).toInt() else 0,
                    easing = LinearEasing)) { contentProgress = value }
            }
            contentProgress = 0f
            close(this@DetailMotion, prepare(this@DetailMotion), ::finishExit)
        }
    }

    private fun finishExit() {
        val action = pendingExit
        pendingExit = null
        action?.invoke()
    }

    fun dispose() {
        animation?.cancel()
        dragging = false
        // A resize retires this controller; complete an already committed return exactly once.
        if (exiting) finishExit()
    }
}

/** Evaluate [blocked] in the caller's composition, even when a Glass overlay owns the state read. */
@Composable
internal fun BlockDetailDrag(blocked: Boolean) {
    val motion = LocalDetailMotion.current
    SideEffect { motion?.dragBlocked = blocked }
    DisposableEffect(motion) { onDispose { motion?.dragBlocked = false } }
}

private suspend fun captureReturnLayer(layer: GraphicsLayer?): ImageBitmap? = try {
    layer?.takeIf { it.size.width > 0 && it.size.height > 0 }?.toImageBitmap()
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }

private class DetailTouch {
    var down = false
    var horizontal = false
    var owned = false
    var travelled = Offset.Zero
}

/** Layered entrance; edge-back commits use the same complete exit as the toolbar button. */
@Composable
internal fun CoverDetailTransition(
    entryKey: String, origin: CoverOrigin?, onExit: () -> Unit,
    comicId: Int? = origin?.comicId, sourcePageKey: String? = origin?.sourcePageKey,
    content: @Composable () -> Unit,
) {
    var entered by rememberSaveable(entryKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val slop = LocalViewConfiguration.current.touchSlop
    val tablet = LocalTabletLayoutEnabled.current
    val latestExit by rememberUpdatedState(onExit)
    val returnHost = LocalCoverReturnHost.current
    val backdropLayer = rememberGraphicsLayer()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val source = origin?.takeIf { it.viewport.width == constraints.maxWidth && it.viewport.height == constraints.maxHeight }
        val sourceBounds = source?.bounds?.takeIf { it.width > 0f && it.height > 0f }
        val animateEntrance = sourceBounds != null && source.background != null
        var windowPosition by remember { mutableStateOf(Offset.Zero) }
        val motion = remember(entryKey, width, height) {
            DetailMotion(scope, height, density, entered || !animateEntrance, viewport = { Rect(windowPosition, Size(width, height)) }, prepare = { closing ->
                // Wait for hidden metadata to be recorded before capturing the committed exit.
                withFrameNanos { }
                withFrameNanos { }
                ReturnLayers(captureReturnLayer(backdropLayer),
                    if (closing.returnCoverVisible) captureReturnLayer(closing.cover?.second) else null)
            }) { closing, layers, after ->
                val destination = closing.cover?.first?.translate(-windowPosition)
                val rect = if (sourceBounds != null && destination != null)
                    coverEntryBounds(sourceBounds, destination, closing.coverProgress) else destination ?: sourceBounds
                val size = Size(width, height)
                val frame = CoverReturnFrame(
                    background = layers.background,
                    cover = layers.cover,
                    backgroundBounds = coverPoseBounds(Rect(Offset.Zero, size), closing.pose, size),
                    coverBounds = if (closing.returnCoverVisible) rect?.let { coverPoseBounds(it, closing.pose, size) } else null,
                    reveal = closing.coverProgress,
                    dim = if (closing.coverProgress < 1f || closing.pose == CoverPose()) 0f else closing.pose.dim,
                    viewport = IntSize(width.toInt(), height.toInt()), comicId = comicId,
                    sourcePageKey = sourcePageKey, sourceBounds = sourceBounds,
                )
                if (returnHost != null) returnHost.close(frame, after) else after()
            }.apply { sourceCover = source?.background?.let { bitmap -> sourceBounds?.let { bitmap to it } } }
        }
        val touch = remember(motion) { DetailTouch() }
        DisposableEffect(motion) { onDispose { motion.dispose() } }
        LaunchedEffect(motion) {
            if (!entered) {
                entered = true
                if (animateEntrance) motion.enter()
            }
        }
        // Drain edge progress without mutating detail layers. Cancellation changes nothing;
        // only a committed gesture starts the exact same exit as BackIconButton.
        HierarchicalBackHandler { motion.dismiss { latestExit() } }
        val nested = remember(motion) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!motion.dragging || source != NestedScrollSource.UserInput) return Offset.Zero
                    motion.drag(Offset(0f, available.y))
                    return Offset(0f, available.y)
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || !touch.down || touch.horizontal ||
                        abs(touch.travelled.y) <= slop || abs(available.y) < .5f || motion.dragBlocked
                    ) return Offset.Zero
                    if (!motion.dragging) {
                        if (!motion.beginDrag()) return Offset.Zero
                    }
                    touch.owned = true
                    motion.drag(Offset(0f, available.y))
                    return Offset(0f, available.y)
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    return if (touch.owned) Velocity(0f, available.y) else Velocity.Zero
                }
            }
        }
        val gesture = Modifier.nestedScroll(nested).pointerInput(motion, tablet) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val directDragAllowed = canStartDirectDetailDrag(tablet, first.position + windowPosition, motion.cover?.first)
                touch.down = true
                touch.horizontal = false
                touch.owned = false
                touch.travelled = Offset.Zero
                val tracker = VelocityTracker().apply { addPosition(first.uptimeMillis, first.position) }
                var previous = first.position
                var completed = false
                var owned = false
                try {
                    do {
                        val initial = awaitPointerEvent(PointerEventPass.Initial)
                        val change = initial.changes.firstOrNull { it.id == first.id } ?: break
                        val delta = change.position - previous
                        previous = change.position
                        tracker.addPosition(change.uptimeMillis, change.position)
                        touch.travelled = change.position - first.position
                        if (initial.changes.count { it.pressed } > 1) {
                            owned = owned || motion.dragging
                            motion.cancelDrag()
                            touch.horizontal = true
                        }
                        if (!motion.dragging && !touch.owned && abs(touch.travelled.x) > slop &&
                            abs(touch.travelled.x) > abs(touch.travelled.y)) touch.horizontal = true
                        val before = motion.pose
                        val wasDragging = motion.dragging
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (!touch.horizontal && !motion.dragBlocked && change.pressed && !change.isConsumed &&
                            abs(touch.travelled.y) > slop && (motion.dragging || directDragAllowed) &&
                            (motion.dragging || abs(touch.travelled.y) > abs(touch.travelled.x))
                        ) {
                            if (motion.dragging) {
                                // Nested scroll already applied this event when the pointer is over a scroller.
                                if (before == motion.pose) motion.drag(delta)
                                change.consume()
                                owned = true
                            } else if (motion.beginDrag()) {
                                // Start at the interception point, without jumping by the accumulated slop.
                                change.consume()
                                owned = true
                            }
                        }
                        if (!wasDragging && motion.dragging) {
                            tracker.resetTracking()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            touch.owned = true
                        }
                        if ((owned || touch.owned) && touch.horizontal) final.changes.forEach { it.consume() }
                        if (final.changes.none { it.pressed }) {
                            completed = true
                            motion.release(tracker.calculateVelocity().y) { latestExit() }
                        }
                    } while (final.changes.any { it.pressed })
                } finally {
                    touch.down = false
                    if (!completed) motion.cancelDrag()
                }
            }
        }
        val backgroundColor = androidx.compose.material3.MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxSize().onGloballyPositioned { windowPosition = it.positionInWindow() }
            .background(backgroundColor)) {
            source?.background?.let { Image(it, null, Modifier.fillMaxSize().drawWithContent {
                drawContent()
                // The thumbnail now belongs to the flying layer, so do not leave a second copy.
                if (motion.coverInFlight && motion.returnCoverVisible && sourceBounds != null) {
                    drawRect(backgroundColor, sourceBounds.topLeft, sourceBounds.size)
                }
            }, contentScale = ContentScale.FillBounds) }
            Canvas(Modifier.fillMaxSize()) {
                val dim = if (motion.coverProgress < 1f || (motion.coverInFlight && !motion.exiting)) 0f else motion.pose.dim
                drawRect(Color.Black.copy(alpha = dim))
            }
            CompositionLocalProvider(LocalDetailMotion provides motion, LocalDetailEntry provides motion) {
                Box(gesture.fillMaxSize().graphicsLayer {
                    scaleX = motion.pose.scale; scaleY = motion.pose.scale
                    translationX = motion.pose.x; translationY = motion.pose.y
                    clip = true
                    shape = DetailRevealShape(motion.coverProgress)
                }.drawWithContent {
                    backdropLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(backdropLayer)
                }) { content() }
            }
            if (motion.coverInFlight && motion.returnCoverVisible) {
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = motion.pose.scale; scaleY = motion.pose.scale
                    translationX = motion.pose.x; translationY = motion.pose.y
                }) {
                    val target = motion.cover
                    val destination = target?.first?.translate(-windowPosition)
                    val start = sourceBounds
                    val rect = when {
                        start != null && destination != null -> coverEntryBounds(start, destination, motion.coverProgress)
                        start != null -> start
                        else -> destination
                    }
                    if (rect != null && rect.width > 0f && rect.height > 0f) {
                        val coverLayer = target?.second?.takeIf { it.size.width > 0 && it.size.height > 0 }
                        coverLayer?.let { layer ->
                            translate(rect.left, rect.top) {
                                scale(rect.width / layer.size.width, rect.height / layer.size.height, Offset.Zero) {
                                    drawLayer(layer)
                                }
                            }
                        }
                        // Keep the already-visible thumbnail until its destination image is ready;
                        // blend to the destination display list only near the end of the single flight.
                        if (start != null && source.background != null) {
                            drawCoverBitmap(source.background, rect,
                                alpha = if (!motion.coverReady || coverLayer == null) 1f else ((1f - motion.coverProgress) / .25f).coerceIn(0f, 1f),
                                source = start)
                        }
                    }
                }
            }
            if (motion.blocksInput) {
                // Alpha-only child layers keep their layout; block taps during the short entrance.
                Box(Modifier.fillMaxSize().pointerInput(motion) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                })
            }
        }
    }
}
