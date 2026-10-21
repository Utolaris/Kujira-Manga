package com.par9uet.jm.ui.interaction

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Fit the image first; letterbox space must never become extra panning room. */
internal class ImageViewportTransform {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = Size.Zero
    private var aspectRatio = 1f

    fun resize(size: Size, ratio: Float) {
        viewport = size
        aspectRatio = ratio.takeIf { it.isFinite() && it > 0f } ?: 1f
        offset = constrained(offset)
    }

    fun transform(zoom: Float, pan: Offset) {
        if (!zoom.isFinite() || zoom <= 0f || !pan.x.isFinite() || !pan.y.isFinite()) return
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = constrained(offset + pan)
    }

    private fun constrained(value: Offset): Offset {
        if (scale == 1f || viewport.width <= 0f || viewport.height <= 0f) return Offset.Zero
        val imageWidth = minOf(viewport.width, viewport.height * aspectRatio)
        val imageHeight = imageWidth / aspectRatio
        val maxX = ((imageWidth * scale - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((imageHeight * scale - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
}
