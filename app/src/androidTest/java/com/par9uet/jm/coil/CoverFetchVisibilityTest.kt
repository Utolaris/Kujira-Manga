package com.par9uet.jm.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.EventListener
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Test

/** Exercise Coil's real cache -> fetch -> decode chain, not an interceptor returning a result. */
class CoverFetchVisibilityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun coldNetworkFetchWaitsForScrollingToStop() = runBlocking {
        val file = redCover()
        val gate = CoverWorkGate(scrolling = true)
        val entered = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val loader = ImageLoader.Builder(context).diskCache(null).components {
            add(VisibleCoverFetcherFactory())
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = Fetcher {
                    fetches.incrementAndGet()
                    SourceResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), "image/png", DataSource.NETWORK)
                }
            })
            add(VisibleCoverDecoderFactory())
        }.eventListener(object : EventListener {
            override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
                entered.complete(Unit)
            }
        }).build()
        try {
            withTimeout(5_000) {
                val result = async(Dispatchers.IO) { loader.execute(request(gate)) }
                entered.await()
                assertNull(withTimeoutOrNull(200) { result.await() })
                assertEquals(0, fetches.get())
                gate.setVisibility(true, true, scrolling = false)
                assertTrue(result.await() is SuccessResult)
                assertEquals(1, fetches.get())
            }
        } finally { loader.shutdown(); file.delete() }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    @Test fun diskCacheStillLoadsDuringScrollingWithoutANetworkCall() = runBlocking {
        val file = redCover()
        val directory = File(context.cacheDir, "cover-fetch-${System.nanoTime()}")
        val cache = DiskCache.Builder().directory(directory).build()
        val editor = checkNotNull(cache.openEditor(jmCoverCacheKey(42)))
        cache.fileSystem.write(editor.metadata) { }
        cache.fileSystem.write(editor.data) { write(file.readBytes()) }
        editor.commit()
        val loader = ImageLoader.Builder(context).diskCache(cache).okHttpClient(
            OkHttpClient.Builder().addInterceptor { error("A cached cover must not use the network") }.build(),
        ).components {
            add(VisibleCoverFetcherFactory())
            add(VisibleCoverDecoderFactory())
        }.build()
        try {
            val result = withTimeout(5_000) { loader.execute(request(CoverWorkGate(scrolling = true))) }
            assertTrue(result is SuccessResult)
            result as SuccessResult
            assertEquals(DataSource.DISK, result.dataSource)
            assertEquals(Color.RED, (result.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
        } finally { loader.shutdown(); cache.clear(); directory.deleteRecursively(); file.delete() }
    }

    private fun request(gate: CoverWorkGate) = ImageRequest.Builder(context)
        .data("https://covers.example/media/albums/42_3x4.jpg")
        .memoryCacheKey(jmCoverCacheKey(42)).diskCacheKey(jmCoverCacheKey(42))
        .setParameter(CoverWorkGateParameter, gate, memoryCacheKey = null)
        .size(8, 8).allowHardware(false).build()

    private fun redCover(): File = File.createTempFile("cover-fetch", ".png", context.cacheDir).also { file ->
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
