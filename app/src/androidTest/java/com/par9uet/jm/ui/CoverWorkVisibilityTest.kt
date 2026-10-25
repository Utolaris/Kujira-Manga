package com.par9uet.jm.ui

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import coil.EventListener
import coil.decode.Decoder
import coil.decode.DecodeResult
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import com.par9uet.jm.coil.VisibleCoverDecoderFactory
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.ui.components.CoverCrossfadeImage
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.components.CoverViewportItem
import com.par9uet.jm.ui.components.LocalCoverWorkActive
import com.par9uet.jm.ui.components.LocalComicGridScrolling
import com.par9uet.jm.ui.components.isCoverPageVisible
import com.par9uet.jm.ui.components.rememberVisibleCoverKeys
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CoverWorkVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var loader: ImageLoader? = null
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var pager: PagerState
    private lateinit var grid: LazyGridState
    private lateinit var scope: CoroutineScope

    @After fun tearDown() { loader?.shutdown() }

    @Test fun scrollBoundariesOnlyInvalidateTheCoverAndReleaseItsDeferredResult() {
        var scrolling by mutableStateOf(true)
        var containerCompositions = 0
        val completed = AtomicBoolean(false)
        val imageLoader = ImageLoader.Builder(compose.activity).components {
            add { chain ->
                completed.set(true)
                SuccessResult(ColorDrawable(android.graphics.Color.RED), chain.request, DataSource.NETWORK)
            }
        }.build()
        loader = imageLoader
        val resolver = CoverImageHostResolver(knownHosts = listOf("covers.example"))
        compose.setContent {
            val readScrolling = remember { { scrolling } }
            SideEffect { containerCompositions++ }
            CompositionLocalProvider(LocalComicGridScrolling provides readScrolling) {
                Box(Modifier.size(64.dp).background(Color.White)) {
                    JmCoverImage(
                        comicId = 42, remoteHost = "covers.example", contentDescription = null,
                        modifier = Modifier.fillMaxSize().testTag("scroll-gated-cover"),
                        imageLoader = imageLoader, resolver = resolver,
                    )
                }
            }
        }
        compose.waitUntil(5_000) { completed.get() }
        compose.waitForIdle()
        val initialCompositions = compose.runOnIdle { containerCompositions }
        var pixels = compose.onNodeWithTag("scroll-gated-cover").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[pixels.width / 2, pixels.height / 2])
        compose.runOnIdle { scrolling = false }
        compose.waitForIdle()
        pixels = compose.onNodeWithTag("scroll-gated-cover").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
        compose.runOnIdle { scrolling = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(initialCompositions, containerCompositions) }
    }

    @Test fun diskFileIsDecodedAndDisplayedDuringScrollingThroughProductionDecoder() {
        val decoded = AtomicBoolean(false)
        val cached = File.createTempFile("cached-cover", ".png", compose.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val imageLoader = ImageLoader.Builder(compose.activity)
            .components {
                add(object : Fetcher.Factory<Uri> {
                    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                        SourceResult(ImageSource(cached.toOkioPath(), FileSystem.SYSTEM), "image/png", DataSource.DISK)
                    }
                })
                add(VisibleCoverDecoderFactory())
            }
            .eventListener(object : EventListener {
                override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
                    assertTrue(result != null)
                    decoded.set(true)
                }
            })
            .build()
        loader = imageLoader
        val resolver = CoverImageHostResolver(knownHosts = listOf("covers.example"))
        try {
            compose.setContent {
                JmCoverImage(
                    comicId = 42,
                    remoteHost = "covers.example",
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).testTag("cached-cover"),
                    isScrolling = true,
                    imageLoader = imageLoader,
                    resolver = resolver,
                )
            }
            compose.waitUntil(5_000) { decoded.get() }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("cached-cover").captureToImage().toPixelMap()
            assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
        } finally { cached.delete() }
    }

    @Test fun retainedPageWaitsForVisibilityAndReturningKeepsTheDisplayedCovers() {
        showRetainedGrids()
        compose.waitUntil(5_000) { count(1) > 0 }
        compose.runOnIdle { assertEquals(0, count(101)) }
        val original = count(1)
        compose.runOnIdle { scope.launch { pager.scrollToPage(1) } }
        compose.waitUntil(5_000) { count(101) > 0 }
        compose.runOnIdle { scope.launch { pager.scrollToPage(0) } }
        compose.waitForIdle()
        assertEquals(original, count(1))
    }

    @Test fun gridPrefetchDoesNotStartOffscreenCoversAndScrollingReleasesTheNewVisibleRows() {
        showRetainedGrids()
        compose.waitUntil(5_000) { count(1) > 0 }
        compose.runOnIdle {
            assertEquals(0, count(21))
            scope.launch { grid.scrollToItem(20) }
        }
        compose.waitUntil(5_000) { count(21) > 0 }
        assertEquals(0, count(101))
    }

    @Test fun bitmapFadeKeepsTheOldImageAndRoundedClipAndDoesNotReplayAfterHiding() {
        val blue = BitmapPainter(ImageBitmap(8, 8).apply {
            asAndroidBitmap().eraseColor(android.graphics.Color.BLUE)
        })
        val red = BitmapPainter(ImageBitmap(8, 8).apply {
            asAndroidBitmap().eraseColor(android.graphics.Color.RED)
        })
        var painter by mutableStateOf(blue)
        var active by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(64.dp).background(Color.White)) {
                CoverCrossfadeImage(
                    painter, null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).testTag("fade"),
                    isActive = active,
                )
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { painter = red }
        compose.mainClock.advanceTimeBy(96)
        var pixels = compose.onNodeWithTag("fade").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(center.red > .05f && center.blue > .05f && center.green < .05f)
        assertEquals(Color.White, pixels[0, 0])
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { active = true }
        compose.mainClock.advanceTimeBy(32)
        pixels = compose.onNodeWithTag("fade").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
    }

    private fun count(id: Int): Int = requests[jmCoverCacheKey(id)]?.get() ?: 0

    private fun showRetainedGrids() {
        val imageLoader = ImageLoader.Builder(compose.activity).components {
            add { chain ->
                requests.computeIfAbsent(chain.request.memoryCacheKey!!.key) { AtomicInteger() }.incrementAndGet()
                SuccessResult(ColorDrawable(android.graphics.Color.RED), chain.request, DataSource.NETWORK)
            }
        }.build()
        loader = imageLoader
        val resolver = CoverImageHostResolver(knownHosts = listOf("covers.example"))
        compose.setContent {
            val pagerState = rememberPagerState { 2 }
            val coroutineScope = rememberCoroutineScope()
            SideEffect { pager = pagerState; scope = coroutineScope }
            HorizontalPager(pagerState, beyondViewportPageCount = 1, modifier = Modifier.size(220.dp, 240.dp)) { page ->
                val active by remember(page, pagerState) {
                    derivedStateOf {
                        isCoverPageVisible(page, pagerState.currentPage, pagerState.currentPageOffsetFraction)
                    }
                }
                val gridState = rememberLazyGridState()
                val visible = rememberVisibleCoverKeys(gridState)
                if (page == 0) SideEffect { grid = gridState }
                CompositionLocalProvider(LocalCoverWorkActive provides active) {
                    LazyVerticalGrid(GridCells.Fixed(1), state = gridState, modifier = Modifier.fillMaxSize()) {
                        items((1..30).map { page * 100 + it }, key = { it }) { id ->
                            CoverViewportItem(visible, id) {
                                JmCoverImage(
                                    comicId = id,
                                    remoteHost = "covers.example",
                                    contentDescription = null,
                                    modifier = Modifier.height(100.dp),
                                    imageLoader = imageLoader,
                                    resolver = resolver,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
