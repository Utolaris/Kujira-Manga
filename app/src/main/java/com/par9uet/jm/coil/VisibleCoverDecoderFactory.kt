package com.par9uet.jm.coil

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.Decoder
import coil.decode.ImageDecoderDecoder
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/** Only tagged covers participate; article and other untagged requests keep their normal path. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VisibleCoverDecoderFactory(
    private val thumbnailCache: CoverThumbnailCache? = null,
) : Decoder.Factory {
    // Disk hits decode up to 3 in parallel with zero frame-wait when idle; scrolling retains
    // per-frame publication pacing to keep fling frame times smooth.
    private val scheduler = CoverDecodeScheduler(
        maxParallelism = 2,
        diskMaxParallelism = 3,
        paceIdle = false,
        awaitFrame = ::awaitCoverDecodeFrame,
    )
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)
    private val animated = ImageDecoderDecoder.Factory()
    private val bitmap = BitmapFactoryDecoder.Factory(maxParallelism = 2)

    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val gate = options.parameters.value<CoverWorkGate>(CoverWorkGateParameter) ?: return null
        return Decoder {
            scheduler.decode(gate, result.dataSource) {
                withContext(decodeDispatcher) {
                    // Format sniffing/header reads must also wait for a permit and run off Main.
                    val animation = animated.create(result, options, imageLoader)
                    if (animation != null) return@withContext animation.decode()
                    val decoded = if (
                        options.config == Bitmap.Config.ARGB_8888 ||
                        options.config == Bitmap.Config.HARDWARE ||
                        options.allowRgb565
                    ) {
                        try {
                            CoverBitmapDecoder(result.source, options).decode()
                        } catch (_: ImageDecoder.DecodeException) {
                            // Retain BitmapFactory's support for inputs rejected by the native path.
                            bitmap.create(result, options, imageLoader).decode()
                        }
                    } else {
                        bitmap.create(result, options, imageLoader).decode()
                    }
                    // Persist a compact 360x480 WebP thumbnail in the background once per cover
                    // when decoding an original cover (not already served from the thumb cache).
                    val cacheKey = options.diskCacheKey
                    if (thumbnailCache != null &&
                        cacheKey != null &&
                        result.mimeType != "image/webp" &&
                        options.diskCachePolicy.writeEnabled
                    ) {
                        runCatching { result.source.file().toFile() }.getOrNull()?.let { file ->
                            thumbnailCache.scheduleWriteFromOriginal(cacheKey, file)
                        }
                    }
                    decoded
                }
            }
        }
    }
}
