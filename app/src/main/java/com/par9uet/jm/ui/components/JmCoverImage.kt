package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import coil.size.Size
import coil.size.SizeResolver
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.CoverWorkGate
import com.par9uet.jm.coil.CoverWorkGateParameter
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.coil.nextCoverCandidateUrl
import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.image.normalizeJmImageHost
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import org.koin.compose.getKoin

/** Coil-backed JM cover with one-at-a-time CDN fallback and one logical cache identity. */
@Composable
internal fun JmCoverImage(
    comicId: Int,
    remoteHost: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    isScrolling: Boolean = false,
    imageLoader: ImageLoader? = null,
    onReady: ((Boolean) -> Unit)? = null,
    resolver: CoverImageHostResolver = getKoin().get(),
) {
    val context = LocalContext.current
    val pageActive = LocalCoverWorkActive.current
    val inViewport = LocalCoverInViewport.current
    val scrolling = isScrolling || LocalComicGridScrolling.current()
    val workGate = remember(comicId) {
        CoverWorkGate(active = pageActive, visible = inViewport, scrolling = scrolling)
    }
    SideEffect { workGate.setVisibility(pageActive, inViewport, scrolling) }
    val workVisible by workGate.changes.collectAsState(initial = workGate.isReady)
    val loaderHolder: com.par9uet.jm.coil.CoverImageLoaderHolder = getKoin().get()
    val holderLoader by loaderHolder.loader.collectAsState()
    val activeImageLoader = imageLoader ?: holderLoader
    val scrollingState = rememberUpdatedState(scrolling)
    val networkGeneration by resolver.networkGeneration.collectAsState()
    val candidateUrls = remember(comicId, remoteHost, networkGeneration) {
        resolver.coverUrls(comicId, remoteHost)
    }
    var attemptedUrls by remember(comicId, remoteHost, networkGeneration) {
        mutableStateOf(emptySet<String>())
    }
    val candidateUrl = nextCoverCandidateUrl(candidateUrls, attemptedUrls)
    val cacheKey = remember(comicId) { jmCoverCacheKey(comicId) }
    val displayGate = remember(comicId, candidateUrl) { CoverImageDisplayGate() }
    var requestFinished by remember(comicId, candidateUrl) { mutableStateOf(false) }
    // 网络恢复或 CDN 切换仍是同一本漫画：保留已显示封面，等新结果就绪再过渡。
    // 只有漫画身份变化才清空，避免回前台换候选地址时闪回透明占位。
    var displayedCover by remember(comicId) { mutableStateOf<DisplayedCover?>(null) }
    val sizeResolver = remember(workGate) { CoverSizeResolver(workGate) }
    val request = remember(context, candidateUrl, cacheKey, sizeResolver) {
        candidateUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .setParameter(CoverWorkGateParameter, workGate, memoryCacheKey = null)
                // A larger cached bitmap is valid for a smaller grid cell. Also avoid CPU
                // upscaling when the server's cover is smaller than the display slot.
                .precision(Precision.INEXACT)
                // Keep the same request size that AsyncImage derives from the grid item.
                .size(sizeResolver)
                .build()
        }
    }

    // Keep the request remembered across tab switches: an in-flight download can finish without
    // restarting. Size resolution gates new requests; the decoder rechecks visibility after fetch.
    @Suppress("UNUSED_VARIABLE")
    val requestPainter = rememberAsyncImagePainter(
        model = request,
        imageLoader = activeImageLoader,
        contentScale = contentScale,
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    requestFinished = true
                    val loadedUrl = candidateUrl
                    if (loadedUrl != null) {
                        if (state.result.dataSource == DataSource.NETWORK) {
                            // Coil 全量加载耗时（排队/下载/解码/渲染）不是 TTFB，
                            // 只标记主机健康，不写入 Reader 排序使用的延迟 EWMA。
                            resolver.recordHealthy(loadedUrl)
                        }
                        if ((!workGate.isReady && !state.result.dataSource.isMemorySource()) ||
                            shouldDeferCoverResult(scrollingState.value, state.result.dataSource)
                        ) {
                            displayGate.deferSuccess(state.painter, state.result.dataSource)
                        } else {
                            // Cached covers remain drawable during a fling, including disk hits.
                            displayGate.clearDeferredPainter()
                            displayedCover = DisplayedCover(
                                painter = state.painter,
                                animate = shouldAnimateCoverResult(scrollingState.value, state.result.dataSource),
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            log(
                                "CoverImage",
                                "JM$comicId loaded from ${normalizeJmImageHost(loadedUrl)} " +
                                    "(${state.result.dataSource})",
                            )
                        }
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    requestFinished = true
                    val failedUrl = candidateUrl
                    if (failedUrl != null) {
                        val failureKind = classifyImageHostFailure(state.result.throwable)
                        // 只有主机/网络级失败才全局冷却 CDN；资源级失败仅回退到下一个候选
                        if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
                            resolver.recordHostFailure(failedUrl)
                        }
                        if (failureKind != ImageHostFailureKind.CANCELLED) {
                            val updatedAttempts = attemptedUrls + failedUrl
                            val nextUrl = nextCoverCandidateUrl(candidateUrls, updatedAttempts)
                            if (BuildConfig.DEBUG) {
                                log(
                                    "CoverImage",
                                    "JM$comicId host ${normalizeJmImageHost(failedUrl)} failed: " +
                                        "${state.result.throwable::class.java.simpleName}($failureKind); " +
                                        "fallbackHost=${nextUrl?.let(::normalizeJmImageHost)}; " +
                                        "attempts=${updatedAttempts.size}",
                                )
                            }
                            attemptedUrls = updatedAttempts
                        }
                    }
                }
                else -> { requestFinished = false }
            }
        },
    )

    val foregroundWork = loaderHolder.foregroundWork
    SideEffect {
        foregroundWork.update(workGate, pageActive && inViewport &&
            (scrolling || (candidateUrl != null && !requestFinished)))
    }
    DisposableEffect(foregroundWork, workGate) {
        onDispose { foregroundWork.update(workGate, false) }
    }

    // Reuse completed painters after a visibility change. Cached results may be published while
    // scrolling; network results still wait for the fling to settle. No second request is needed.
    LaunchedEffect(scrolling, workVisible, displayGate) {
        if (workVisible) {
            displayGate.takeDeferredCover(scrolling)?.let { result ->
                displayedCover = DisplayedCover(
                    result.painter,
                    animate = shouldAnimateCoverResult(scrolling, result.dataSource),
                )
            }
        }
    }

    SideEffect { onReady?.invoke(displayedCover != null) }
    CoverCrossfadeImage(
        painter = displayedCover?.painter,
        skipAnimation = onReady != null || displayedCover?.animate == false,
        contentDescription = contentDescription,
        contentScale = contentScale,
        isActive = workVisible,
        modifier = modifier.then(sizeResolver),
    )
}

/** 当前应上屏的封面与是否需要淡入动画（内存缓存命中不需要）。 */
private data class DisplayedCover(val painter: Painter, val animate: Boolean)

internal fun shouldDeferCoverResult(isScrolling: Boolean, dataSource: DataSource): Boolean =
    isScrolling && dataSource == DataSource.NETWORK

internal fun shouldAnimateCoverResult(isScrolling: Boolean, dataSource: DataSource): Boolean =
    !dataSource.isMemorySource() && !(isScrolling && dataSource == DataSource.DISK)

/**
 * The public AsyncImage API keeps this resolver internal, so mirror its grid-size behavior here.
 * 供 [JmCoverImage] 与 [LocalCoverImage] 共用：裸 model 的 rememberAsyncImagePainter 会挂起等待
 * drawSize（官方文档明示），必须显式给请求 size 才不会死锁。
 */
internal class CoverSizeResolver(private val workGate: CoverWorkGate? = null) : SizeResolver, LayoutModifier {
    private val currentConstraints = MutableStateFlow(Constraints(0, 0, 0, 0))

    override suspend fun size(): Size {
        workGate?.awaitReady()
        return currentConstraints.mapNotNull { it.toCoilSizeOrNull() }.first()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        currentConstraints.value = constraints
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

private fun Constraints.toCoilSizeOrNull(): Size? {
    if (minWidth == 0 && maxWidth == 0 && minHeight == 0 && maxHeight == 0) {
        return null
    }
    val width = if (hasBoundedWidth) {
        Dimension.Pixels(maxWidth)
    } else {
        Dimension.Undefined
    }
    val height = if (hasBoundedHeight) {
        Dimension.Pixels(maxHeight)
    } else {
        Dimension.Undefined
    }
    return Size(width, height)
}

internal data class CompletedCover(val painter: Painter, val dataSource: DataSource)

/** Retains the source so a cached result does not inherit the network-only scroll delay. */
internal class CoverImageDisplayGate {
    private var deferredCover: CompletedCover? = null

    fun deferSuccess(painter: Painter, dataSource: DataSource = DataSource.NETWORK) {
        deferredCover = CompletedCover(painter, dataSource)
    }

    fun takeDeferredCover(isScrolling: Boolean = false): CompletedCover? {
        val result = deferredCover ?: return null
        if (shouldDeferCoverResult(isScrolling, result.dataSource)) return null
        deferredCover = null
        return result
    }

    fun clearDeferredPainter() {
        deferredCover = null
    }
}
