package com.par9uet.jm.coil

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.decode.DecodeResult
import coil.decode.DecodeUtils
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.request.Options
import coil.size.Dimension
import com.par9uet.jm.core.concurrent.BackgroundWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * File-backed static covers: native scaling/orientation, optional low-RAM (RGB_565 for opaque
 * images) policy for dense grids, and a final immutable bitmap allocation.
 */
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
            // Dense grid covers opt into MEMORY_POLICY_LOW_RAM (RGB_565 for opaque JPEGs/WebPs),
            // halving memory/VRAM footprint while detail views keep full precision.
            decoder.memorySizePolicy = if (options.allowRgb565) {
                ImageDecoder.MEMORY_POLICY_LOW_RAM
            } else {
                ImageDecoder.MEMORY_POLICY_DEFAULT
            }
            decoder.isUnpremultipliedRequired = !options.premultipliedAlpha
            options.colorSpace?.let(decoder::setTargetColorSpace)
        }
        bitmap.density = options.context.resources.displayMetrics.densityDpi
        DecodeResult(BitmapDrawable(options.context.resources, bitmap), sampled)
    }
}

/**
 * 本地封面缩略图缓存（`common/thumbs/{key}.webp`）。
 * 官方图床只能拉原图；首次解原图时在后台顺手落一份 480x640 WebP 缩略图（~25-40KB，兼顾手机与平板网格），
 * 后续网格磁盘命中直接解缩略图，避免每次重复解压整张原图，并大幅提升同等磁盘配额下的封面留存量。
 */
internal class CoverThumbnailCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val inFlightWrites = ConcurrentHashMap.newKeySet<String>()
    private val writesSinceTrim = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + BackgroundWork().context)

    fun isEligibleSize(options: Options): Boolean {
        // Only grid cards (lowRam / allowRgb565) use thumbnails; detail hero covers keep full-res.
        if (!options.allowRgb565) return false
        val targetW = (options.size.width as? Dimension.Pixels)?.px
        val targetH = (options.size.height as? Dimension.Pixels)?.px
        if (targetW == null && targetH == null) return false
        return (targetW == null || targetW <= MAX_THUMB_WIDTH) &&
            (targetH == null || targetH <= MAX_THUMB_HEIGHT)
    }

    fun getIfPresent(cacheKey: String): File? {
        val file = fileFor(cacheKey) ?: return null
        if (!file.isFile || file.length() <= 0L) return null
        // Touch mtime for LRU trimming on best-effort basis.
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    /**
     * Copies raw compressed bytes while [sourceFile] is open, then asynchronously decodes at
     * <= 360x480 and writes a compact WebP thumbnail without blocking the caller.
     */
    fun scheduleWriteFromOriginal(cacheKey: String, sourceFile: File) {
        val target = fileFor(cacheKey) ?: return
        if (target.isFile && target.length() > 0L) return
        if (sourceFile.length() <= MIN_SOURCE_BYTES_TO_THUMBNAIL) return
        if (!inFlightWrites.add(cacheKey)) return
        val bytes = runCatching { sourceFile.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            inFlightWrites.remove(cacheKey)
            return
        }
        scope.launch {
            try {
                writeThumbnail(target, bytes)
                if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY_N_WRITES) {
                    writesSinceTrim.set(0)
                    trimToMaxBytes()
                }
            } catch (_: Throwable) {
                // Best-effort cache optimization; never fail the primary image pipeline.
            } finally {
                inFlightWrites.remove(cacheKey)
            }
        }
    }

    fun clear() {
        runCatching {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun writeThumbnail(target: File, rawBytes: ByteArray) {
        if (!directory.exists()) directory.mkdirs()
        val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(rawBytes))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(
                MAX_THUMB_WIDTH / w.toDouble(),
                MAX_THUMB_HEIGHT / h.toDouble(),
                1.0,
            )
            if (scale < 1.0) {
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
        val tmp = File(directory, "${target.name}.tmp")
        try {
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(format, WEBP_QUALITY, out)) {
                    tmp.delete()
                    return
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            bitmap.recycle()
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun trimToMaxBytes() {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val targetBytes = (maxBytes * 8L) / 10L
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= targetBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun fileFor(cacheKey: String): File? {
        if (!cacheKey.startsWith(COVER_KEY_PREFIX)) return null
        val id = cacheKey.removePrefix(COVER_KEY_PREFIX)
        if (id.isEmpty() || !id.all { it.isDigit() }) return null
        return File(directory, "$id.webp")
    }

    companion object {
        const val MAX_THUMB_WIDTH = 480
        const val MAX_THUMB_HEIGHT = 640
        private const val COVER_KEY_PREFIX = "jm-cover-"
        private const val WEBP_QUALITY = 82
        private const val MIN_SOURCE_BYTES_TO_THUMBNAIL = 24L * 1024L
        private const val DEFAULT_MAX_BYTES = 96L * 1024L * 1024L
        private const val TRIM_EVERY_N_WRITES = 32
    }
}
