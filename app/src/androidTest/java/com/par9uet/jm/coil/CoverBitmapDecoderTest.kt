package com.par9uet.jm.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Base64
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import coil.EventListener
import coil.ImageLoader
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test

class CoverBitmapDecoderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun jpegIsSampledToTheCellAndSmallSourcesAreNotUpscaled() = runBlocking {
        val file = image(1200, 1600, Bitmap.CompressFormat.JPEG)
        try {
            withContext(Dispatchers.IO) {
                for (columns in listOf(2, 3, 4, 6)) {
                    val width = (1080 - (columns + 1) * 12) / columns
                    val height = width * 4 / 3
                    val bitmap = decode(file, Size(width, height))
                    try {
                        assertTrue(kotlin.math.abs(bitmap.width - width) <= 1)
                        assertTrue(kotlin.math.abs(bitmap.height - height) <= 1)
                        assertTrue(bitmap.allocationByteCount.toLong() <= (width + 1L) * (height + 1L) * 4L)
                    } finally { bitmap.recycle() }
                }
                val bitmap = decode(file, Size(2400, 3200))
                try { assertEquals(1200, bitmap.width); assertEquals(1600, bitmap.height) }
                finally { bitmap.recycle() }
                val widthOnly = decode(file, Size(Dimension.Pixels(300), Dimension.Undefined))
                try { assertEquals(300, widthOnly.width); assertEquals(400, widthOnly.height) }
                finally { widthOnly.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun nativeDecoderKeepsAlphaAndHardwareAllocationPolicies() = runBlocking {
        val file = image(120, 160, Bitmap.CompressFormat.PNG, Color.TRANSPARENT)
        try {
            withContext(Dispatchers.IO) {
                val software = decode(file, Size(60, 80))
                try { assertTrue(software.hasAlpha()); assertEquals(Color.TRANSPARENT, software.getPixel(0, 0)) }
                finally { software.recycle() }
                val hardware = decode(file, Size(60, 80), Bitmap.Config.HARDWARE)
                try { assertEquals(Bitmap.Config.HARDWARE, hardware.config); assertFalse(hardware.isMutable) }
                finally { hardware.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun exifRotationUsesTheOrientedSizeAndPreservesTheHardwareBitmap() = runBlocking {
        val file = image(160, 120, Bitmap.CompressFormat.JPEG)
        try {
            // A solid fixture only checks dimensions: an unrotated image could pass.
            val asymmetric = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            try {
                asymmetric.eraseColor(Color.RED)
                android.graphics.Canvas(asymmetric).drawRect(
                    80f, 0f, 160f, 120f, android.graphics.Paint().apply { color = Color.BLUE },
                )
                file.outputStream().use { assertTrue(asymmetric.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { asymmetric.recycle() }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            withContext(Dispatchers.IO) {
                val bitmap = decode(file, Size(60, 80), Bitmap.Config.HARDWARE)
                try {
                    assertEquals(60, bitmap.width); assertEquals(80, bitmap.height)
                    assertEquals(Bitmap.Config.HARDWARE, bitmap.config)
                    val readable = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    try {
                        val top = readable.getPixel(30, 20)
                        val bottom = readable.getPixel(30, 60)
                        assertTrue("Clockwise rotation must put the red half at the top", Color.red(top) > 220 && Color.blue(top) < 30)
                        assertTrue("Clockwise rotation must put the blue half at the bottom", Color.blue(bottom) > 220 && Color.red(bottom) < 30)
                    } finally { readable.recycle() }
                } finally { bitmap.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun largerCellCacheIsReusedWhenSwitchingToDenserGridsButNotTheReverse() = runBlocking {
        val file = image(1200, 1600, Bitmap.CompressFormat.JPEG)
        val decodes = AtomicInteger()
        val loader = ImageLoader.Builder(context).components { add(VisibleCoverDecoderFactory()) }
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(16 * 1024 * 1024).build() }
            .eventListener(object : EventListener {
                override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) { decodes.incrementAndGet() }
            }).build()
        fun request(width: Int) = ImageRequest.Builder(context).data(file)
            .memoryCacheKey("cover-decode-size-reuse").size(width, width * 4 / 3)
            .precision(Precision.INEXACT).allowHardware(false)
            .setParameter(CoverWorkGateParameter, CoverWorkGate(), memoryCacheKey = null).build()
        try {
            assertTrue(loader.execute(request(480)) is SuccessResult)
            val threeColumns = loader.execute(request(320)) as SuccessResult
            val fourColumns = loader.execute(request(240)) as SuccessResult
            assertEquals(coil.decode.DataSource.MEMORY_CACHE, threeColumns.dataSource)
            assertEquals(coil.decode.DataSource.MEMORY_CACHE, fourColumns.dataSource)
            assertEquals(1, decodes.get())
            assertTrue(loader.execute(request(720)) is SuccessResult)
            assertEquals(2, decodes.get())
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun animatedCoverStillUsesTheAnimatedDecoder() = runBlocking {
        val file = File.createTempFile("cover-animation", ".gif", context.cacheDir)
        file.writeBytes(Base64.decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAkAAAEALAAAAAABAAEAAAICRAEAIfkECQAAAQAsAAAAAAEAAQAAAgJEAQA7",
            Base64.DEFAULT,
        ))
        val loader = ImageLoader.Builder(context).components { add(VisibleCoverDecoderFactory()) }.build()
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(file).size(16, 16)
                .setParameter(CoverWorkGateParameter, CoverWorkGate(), memoryCacheKey = null).build())
            assertTrue(result is SuccessResult)
            assertTrue((result as SuccessResult).drawable is AnimatedImageDrawable)
        } finally { loader.shutdown(); file.delete() }
    }

    private suspend fun decode(file: File, size: Size, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap =
        ImageSource(file.toOkioPath(), FileSystem.SYSTEM).use { source ->
            val options = Options(context, config = config, size = size, scale = Scale.FILL, allowInexactSize = true)
            (CoverBitmapDecoder(source, options).decode().drawable as BitmapDrawable).bitmap
        }

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat, color: Int = Color.RED): File =
        File.createTempFile("cover-native", ".img", context.cacheDir).also { file ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(format, 90, it) }
            } finally { bitmap.recycle() }
        }
}
