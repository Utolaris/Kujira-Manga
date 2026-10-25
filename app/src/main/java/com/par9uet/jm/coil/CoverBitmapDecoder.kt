package com.par9uet.jm.coil

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import coil.decode.DecodeResult
import coil.decode.DecodeUtils
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.runInterruptible
import kotlin.math.roundToInt

/** File-backed static covers: native scaling/orientation and a final immutable bitmap allocation. */
internal class CoverBitmapDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {
    override suspend fun decode(): DecodeResult = runInterruptible {
        var sampled = false
        // Coil's disk snapshot already owns this file. Avoid copying compressed data into a
        // Java byte array; the source stays open until Coil finishes the whole decode request.
        val input = ImageDecoder.createSource(source.file().toFile())
        val bitmap = ImageDecoder.decodeBitmap(input) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val targetWidth = (options.size.width as? Dimension.Pixels)?.px
            val targetHeight = (options.size.height as? Dimension.Pixels)?.px
            var multiplier = when {
                targetWidth == null && targetHeight == null -> 1.0
                targetWidth == null -> targetHeight!! / height.toDouble()
                targetHeight == null -> targetWidth / width.toDouble()
                else -> DecodeUtils.computeSizeMultiplier(width, height, targetWidth, targetHeight, options.scale)
            }
            if (options.allowInexactSize) multiplier = multiplier.coerceAtMost(1.0)
            sampled = multiplier < 1.0
            if (multiplier != 1.0) {
                decoder.setTargetSize(
                    (width * multiplier).roundToInt().coerceAtLeast(1),
                    (height * multiplier).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = if (options.config == Bitmap.Config.HARDWARE) {
                ImageDecoder.ALLOCATOR_HARDWARE
            } else {
                ImageDecoder.ALLOCATOR_SOFTWARE
            }
            // Preserve the request's color/alpha policy. No RGB_565 or resolution downgrade.
            decoder.isUnpremultipliedRequired = !options.premultipliedAlpha
            options.colorSpace?.let(decoder::setTargetColorSpace)
        }
        bitmap.density = options.context.resources.displayMetrics.densityDpi
        DecodeResult(BitmapDrawable(options.context.resources, bitmap), sampled)
    }
}
