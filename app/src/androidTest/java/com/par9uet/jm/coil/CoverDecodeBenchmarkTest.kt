package com.par9uet.jm.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import coil.decode.BitmapFactoryDecoder
import coil.decode.ImageSource
import coil.request.Options
import coil.size.Scale
import coil.size.Size
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic codec-only A/B; timings are observations, not a performance regression gate.
 * The assertions validate decoded output only. Device scheduling/thermal state is uncontrolled.
 */
@LargeTest
class CoverDecodeBenchmarkTest {
    @Test fun compareNativeAndPreviousCodecAtTwoThreeFourAndSixColumns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("cover-codec-benchmark", ".jpg", context.cacheDir)
        val input = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(input)
            val paint = Paint()
            for (y in 0 until 1600 step 8) {
                for (x in 0 until 1200 step 8) {
                    paint.color = Color.rgb((x * 7 + y) % 256, (y * 11 + x) % 256, (x + y * 3) % 256)
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
                }
            }
            file.outputStream().use { input.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { input.recycle() }
        try {
            withContext(Dispatchers.IO) {
                for (columns in listOf(2, 3, 4, 6)) {
                    val width = (1080 - (columns + 1) * 12) / columns
                    val options = Options(context, config = Bitmap.Config.HARDWARE,
                        size = Size(width, width * 4 / 3), scale = Scale.FILL, allowInexactSize = true)
                    fun source() = ImageSource(file.toOkioPath(), FileSystem.SYSTEM)
                    suspend fun decode(native: Boolean): Bitmap = source().use {
                        val result = if (native) CoverBitmapDecoder(it, options).decode()
                            else BitmapFactoryDecoder(it, options).decode()
                        (result.drawable as BitmapDrawable).bitmap
                    }
                    // Prime both codecs before measuring, and alternate ordering between rounds.
                    for (native in listOf(false, true)) decode(native).recycle()
                    repeat(2) { round ->
                        for (native in if (round == 0) listOf(false, true) else listOf(true, false)) {
                            val started = SystemClock.elapsedRealtimeNanos()
                            var cpuNanos = 0L
                            var bytes = 0L
                            repeat(12) {
                                // These direct decode calls do not dispatch to a different worker.
                                val cpuStart = Debug.threadCpuTimeNanos()
                                val bitmap = decode(native)
                                cpuNanos += Debug.threadCpuTimeNanos() - cpuStart
                                try {
                                    assertTrue(kotlin.math.abs(bitmap.width - width) <= 1)
                                    assertTrue(kotlin.math.abs(bitmap.height - width * 4 / 3) <= 1)
                                    org.junit.Assert.assertEquals(Bitmap.Config.HARDWARE, bitmap.config)
                                    assertTrue("Decoded allocation must be nonempty", bitmap.allocationByteCount > 0)
                                    bytes += bitmap.allocationByteCount
                                } finally { bitmap.recycle() }
                            }
                            val wallMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                            val line = "columns=$columns round=$round codec=${if (native) "native" else "previous"} " +
                                "count=12 wallMs=$wallMs cpuMs=${cpuNanos / 1_000_000.0} decodedBytes=$bytes"
                            Log.i("CoverDecodeBenchmark", line)
                            println(line)
                        }
                    }
                }
            }
        } finally { file.delete() }
    }
}
