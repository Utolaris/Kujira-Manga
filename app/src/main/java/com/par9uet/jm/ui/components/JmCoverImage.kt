package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
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
import coil.size.Size
import coil.size.SizeResolver
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.coil.CoverImageHostResolver
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
    val loaderHolder: com.par9uet.jm.coil.CoverImageLoaderHolder = getKoin().get()
    val holderLoader by loaderHolder.loader.collectAsState()
    val activeImageLoader = imageLoader ?: holderLoader
    val scrollingState = rememberUpdatedState(isScrolling)
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
    // 网络恢复或 CDN 切换仍是同一本漫画：保留已显示封面，等新结果就绪再过渡。
    // 只有漫画身份变化才清空，避免回前台换候选地址时闪回透明占位。
    var displayedCover by remember(comicId) { mutableStateOf<DisplayedCover?>(null) }
    val sizeResolver = remember { CoverSizeResolver() }
    val request = remember(context, candidateUrl, cacheKey, sizeResolver) {
        candidateUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                // Keep the same request size that AsyncImage derives from the grid item.
                .size(sizeResolver)
                .build()
        }
    }

    // The request painter stays remembered so Coil can finish and cache the image while the
    // grid is moving. Only the selected painter below participates in the draw path.
    @Suppress("UNUSED_VARIABLE")
    val requestPainter = rememberAsyncImagePainter(
        model = request,
        imageLoader = activeImageLoader,
        contentScale = contentScale,
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    val loadedUrl = candidateUrl
                    if (loadedUrl != null) {
                        if (state.result.dataSource == DataSource.NETWORK) {
                            // Coil 全量加载耗时（排队/下载/解码/渲染）不是 TTFB，
                            // 只标记主机健康，不写入 Reader 排序使用的延迟 EWMA。
                            resolver.recordHealthy(loadedUrl)
                        }
                        if (shouldDeferCoverResult(scrollingState.value, state.result.dataSource)) {
                            displayGate.deferSuccess(state.painter)
                        } else {
                            // Memory cache hits are cheap to publish even during a fling.
                            displayGate.clearDeferredPainter()
                            displayedCover = DisplayedCover(
                                painter = state.painter,
                                // 内存缓存命中瞬时上屏；磁盘/网络命中淡入 300ms。
                                animate = !state.result.dataSource.isMemorySource(),
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
                else -> Unit
            }
        },
    )

    // Publish a completed non-memory result once after the fling. The painter is reused directly,
    // so settling the grid does not create a second request or a burst of cache lookups.
    // 期刊发表必然来自磁盘/网络（内存命中不走 defer），所以这里统一带淡入。
    LaunchedEffect(isScrolling, displayGate) {
        if (!isScrolling) {
            displayGate.takeDeferredPainter()?.let { painter ->
                displayedCover = DisplayedCover(painter, animate = true)
            }
        }
    }

    SideEffect { onReady?.invoke(displayedCover != null) }
    CoverCrossfadeImage(
        painter = displayedCover?.painter,
        skipAnimation = onReady != null || displayedCover?.animate == false,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.then(sizeResolver),
    )
}

/** 当前应上屏的封面与是否需要淡入动画（内存缓存命中不需要）。 */
private data class DisplayedCover(val painter: Painter, val animate: Boolean)

internal fun shouldDeferCoverResult(isScrolling: Boolean, dataSource: DataSource): Boolean =
    isScrolling && !dataSource.isMemorySource()

/**
 * The public AsyncImage API keeps this resolver internal, so mirror its grid-size behavior here.
 * 供 [JmCoverImage] 与 [LocalCoverImage] 共用：裸 model 的 rememberAsyncImagePainter 会挂起等待
 * drawSize（官方文档明示），必须显式给请求 size 才不会死锁。
 */
internal class CoverSizeResolver : SizeResolver, LayoutModifier {
    private val currentConstraints = MutableStateFlow(Constraints(0, 0, 0, 0))

    override suspend fun size(): Size = currentConstraints
        .mapNotNull { it.toCoilSizeOrNull() }
        .first()

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

/** Tracks a completed painter without putting it into the draw path until scrolling settles. */
internal class CoverImageDisplayGate {
    private var deferredPainter: Painter? = null

    fun deferSuccess(painter: Painter) {
        deferredPainter = painter
    }

    fun takeDeferredPainter(): Painter? {
        val painter = deferredPainter
        deferredPainter = null
        return painter
    }

    fun clearDeferredPainter() {
        deferredPainter = null
    }
}
