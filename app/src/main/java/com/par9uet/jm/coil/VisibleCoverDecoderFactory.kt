package com.par9uet.jm.coil

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.Decoder
import coil.decode.ImageDecoderDecoder
import coil.fetch.SourceResult
import coil.request.Options
import com.par9uet.jm.core.concurrent.BackgroundWork
import kotlinx.coroutines.withContext

/** Only tagged covers participate; article and other untagged requests keep their normal path. */
internal class VisibleCoverDecoderFactory : Decoder.Factory {
    private val scheduler = CoverDecodeScheduler(awaitFrame = ::awaitCoverDecodeFrame)
    private val work = BackgroundWork()
    private val animated = ImageDecoderDecoder.Factory()
    private val bitmap = BitmapFactoryDecoder.Factory(maxParallelism = 1)

    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
        val gate = options.parameters.value<CoverWorkGate>(CoverWorkGateParameter) ?: return null
        return Decoder {
            scheduler.decode(gate, result.dataSource) {
                withContext(work.context) {
                    // Format sniffing/header reads must also wait for a permit and run off Main.
                    val animation = animated.create(result, options, imageLoader)
                    when {
                        animation != null -> animation.decode()
                        !options.allowRgb565 &&
                            (options.config == Bitmap.Config.ARGB_8888 || options.config == Bitmap.Config.HARDWARE) -> {
                            try {
                                CoverBitmapDecoder(result.source, options).decode()
                            } catch (_: ImageDecoder.DecodeException) {
                                // Retain BitmapFactory's support for inputs rejected by the native path.
                                bitmap.create(result, options, imageLoader).decode()
                            }
                        }
                        else -> bitmap.create(result, options, imageLoader).decode()
                    }
                }
            }
        }
    }
}
