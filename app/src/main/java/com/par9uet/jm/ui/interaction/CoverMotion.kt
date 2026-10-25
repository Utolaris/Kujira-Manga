package com.par9uet.jm.ui.interaction

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.max

internal const val CoverEnterMillis = 448
internal const val DetailContentEnterMillis = 224
internal const val CoverReturnMillis = 440
internal const val DetailContentExitMillis = 150
internal const val CoverExitMillis = 256
internal val CoverEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

internal fun coverBoundsMoved(from: Rect, to: Rect): Boolean =
    max(max(abs(from.left - to.left), abs(from.top - to.top)),
        max(abs(from.right - to.right), abs(from.bottom - to.bottom))) > 1f

/** Retarget against the last displayed frame, without discarding the current frame's travel. */
internal class CoverReturnPath(start: Rect?, initialTarget: Rect?, private var fading: Boolean) {
    var bounds: Rect? = start
        private set
    var alpha = 1f
        private set
    private var target = initialTarget
    private var segmentStart = start
    private var segmentFraction = 0f
    private var segmentAlpha = 1f
    private var previousProgress = 0f

    fun advance(progress: Float, nextTarget: Rect?, fadeOut: Boolean) {
        val value = progress.coerceIn(previousProgress, 1f)
        val moved = target != null && nextTarget != null && coverBoundsMoved(target!!, nextTarget)
        if (moved || fading != fadeOut) {
            segmentStart = bounds
            segmentFraction = previousProgress
            segmentAlpha = alpha
            target = nextTarget
            fading = fadeOut
        }
        val fraction = if (segmentFraction < 1f) ((value - segmentFraction) / (1f - segmentFraction)).coerceIn(0f, 1f) else 1f
        val from = segmentStart
        val to = target
        if (from != null && to != null) bounds = coverEntryBounds(from, to, fraction)
        alpha = if (fading) segmentAlpha * (1f - fraction) else 1f
        previousProgress = value
    }
}

internal fun coverHasVisiblePixels(bounds: Rect?, viewport: Rect): Boolean = bounds?.intersect(viewport)
    ?.let { it.width > 0f && it.height > 0f } == true

/** A committed gesture must not squeeze a remaining visible flight into one or two frames. */
internal fun coverExitDuration(reveal: Float, from: Rect?, to: Rect?, fading: Boolean): Int {
    val background = (CoverExitMillis * reveal.coerceIn(0f, 1f)).toInt()
    if (from == null || to == null) return background
    if (fading) return CoverExitMillis
    return max(background, if (coverBoundsMoved(from, to)) CoverExitMillis / 2 else 0)
}

internal fun coverCenterExitBounds(current: Rect, viewport: Size): Rect {
    val halfWidth = current.width * .275f
    val halfHeight = current.height * .275f
    return Rect(viewport.width / 2 - halfWidth, viewport.height / 2 - halfHeight,
        viewport.width / 2 + halfWidth, viewport.height / 2 + halfHeight)
}

internal fun coverPoseBounds(rect: Rect, pose: CoverPose, viewport: Size): Rect {
    fun point(x: Float, y: Float) = Offset(
        (x - viewport.width / 2) * pose.scale + viewport.width / 2 + pose.x,
        (y - viewport.height / 2) * pose.scale + viewport.height / 2 + pose.y,
    )
    return Rect(point(rect.left, rect.top), point(rect.right, rect.bottom))
}

/** Both cover edges and the background reveal use this same eased fraction, without overshoot. */
internal fun coverEntryBounds(source: Rect, destination: Rect, fraction: Float): Rect {
    val f = fraction.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * f
    return Rect(mix(source.left, destination.left), mix(source.top, destination.top),
        mix(source.right, destination.right), mix(source.bottom, destination.bottom))
}

internal data class CoverPose(
    val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f,
    val dim: Float = 1f,
) {
    fun interpolate(to: CoverPose, fraction: Float): CoverPose {
        val f = fraction.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * f
        return CoverPose(mix(scale, to.scale), mix(x, to.x), mix(y, to.y), mix(dim, to.dim))
    }
}

internal fun coverDragPose(offset: Offset, height: Float, direction: Float = sign(offset.y)): CoverPose {
    if (offset.y * direction < 0f) return CoverPose(x = offset.x, y = offset.y * .2f)
    val fraction = (abs(offset.y) / (height * .4f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return CoverPose(1f - .3f * fraction, offset.x, offset.y, dim = 1f - fraction * .85f)
}

internal fun shouldDismissCover(distance: Float, velocity: Float, height: Float, density: Float): Boolean =
    height > 0f && abs(distance) > 0f &&
        (abs(distance) > height * .18f || (velocity * distance > 0f && abs(velocity) > 1200f * density))
