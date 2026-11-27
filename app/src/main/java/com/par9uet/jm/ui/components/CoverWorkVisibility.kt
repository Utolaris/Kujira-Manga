package com.par9uet.jm.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.par9uet.jm.coil.CoverWorkGate
import com.par9uet.jm.coil.CoverWorkGateParameter
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** Retained pager pages keep their state, but only pages intersecting the viewport load covers. */
internal val LocalCoverWorkActive = compositionLocalOf { true }
internal val LocalCoverInViewport = compositionLocalOf { true }

/** Grid precomposition/premeasurement must not start offscreen image requests. */
@Composable
internal fun rememberVisibleCoverKeys(state: LazyGridState): State<Set<Any>> = remember(state) {
    derivedStateOf(structuralEqualityPolicy()) { state.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key } }
}

/** Read visibility inside the item: a new row must not invalidate the grid's content DSL. */
@Composable
internal fun CoverViewportItem(
    visibleKeys: State<Set<Any>>,
    itemKey: Any,
    content: @Composable () -> Unit,
) {
    val visible by remember(visibleKeys, itemKey) { coverItemVisibility(visibleKeys, itemKey) }
    CompositionLocalProvider(LocalCoverInViewport provides visible, content = content)
}

internal fun coverItemVisibility(visibleKeys: State<Set<Any>>, itemKey: Any): State<Boolean> =
    derivedStateOf(structuralEqualityPolicy()) { itemKey in visibleKeys.value }

internal fun isCoverPageVisible(page: Int, currentPage: Int, offsetFraction: Float): Boolean =
    kotlin.math.abs(currentPage - page + offsetFraction) < 1f

/**
 * 列表静止时，对可视区下方 1–2 行已命中本地磁盘/缩略图缓存的封面做低开销内存预解码。
 * 强制 `networkCachePolicy(CachePolicy.DISABLED)`，绝不触发离屏网络下载；
 * 向下滑动时这些行直接命中内存缓存瞬时出图，跳过解码与淡入。
 */
@Composable
internal fun PrefetchDiskCoversBelowViewport(
    gridState: LazyGridState,
    itemCount: Int,
    comicIdAt: (Int) -> Int?,
) {
    val env = LocalCoverPipelineEnvironment.current ?: return
    val active = LocalCoverWorkActive.current
    val remoteHost = LocalRemoteImageHost.current
    val context = LocalContext.current

    LaunchedEffect(gridState, active, itemCount, remoteHost, env.imageLoader, env.networkGeneration) {
        if (!active || itemCount <= 0) return@LaunchedEffect
        snapshotFlow {
            if (gridState.isScrollInProgress) null
            else {
                val visible = gridState.layoutInfo.visibleItemsInfo
                val last = visible.lastOrNull() ?: return@snapshotFlow null
                val columns = visible.groupBy { it.offset.y }.values
                    .maxOfOrNull { it.size }
                    ?.coerceIn(1, 12) ?: 3
                Triple(last.index, last.size.width, columns)
            }
        }.distinctUntilChanged().collect { anchor ->
            if (anchor == null) return@collect
            // 等首屏可见卡片先完成解码，再预热下方约 2 行（随手机/平板实际列数自适应）。
            delay(180L)
            if (gridState.isScrollInProgress) return@collect
            val (lastIndex, itemW, columns) = anchor
            val targetW = bucketCoverDimensionPx(itemW.coerceAtLeast(180))
            val targetH = bucketCoverDimensionPx((targetW * 4) / 3)
            val prefetchCount = (columns * 2).coerceIn(6, 18)
            val gate = CoverWorkGate(active = true, visible = true, scrolling = false)
            val start = (lastIndex + 1).coerceAtLeast(0)
            val end = minOf(itemCount - 1, lastIndex + prefetchCount)
            for (index in start..end) {
                if (gridState.isScrollInProgress) break
                val comicId = comicIdAt(index) ?: continue
                val url = env.resolver.coverUrls(comicId, remoteHost).firstOrNull() ?: continue
                val cacheKey = jmCoverCacheKey(comicId)
                env.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .allowRgb565(true)
                        .precision(Precision.INEXACT)
                        .size(targetW, targetH)
                        .setParameter(CoverWorkGateParameter, gate, memoryCacheKey = null)
                        .build()
                )
            }
        }
    }
}
