package com.par9uet.jm.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.size.Precision
import com.par9uet.jm.coil.CoverWorkGate
import com.par9uet.jm.coil.CoverWorkGateParameter
import com.par9uet.jm.ui.components.currentCoverImageLoader

/** 封面加载未完成时的占位（透明，父级背景透出）。 */
private val CoverPlaceholderPainter = ColorPainter(Color.Transparent)

internal fun DataSource.isMemorySource(): Boolean =
    this == DataSource.MEMORY_CACHE || this == DataSource.MEMORY

/**
 * 封面淡入动画组件，对齐上游 animeko（Sketch crossfade）的语义，所有封面统一复用：
 *
 * - 新 painter 以 alpha 0→1 淡入（默认 300ms），旧 painter 垫在底层继续绘制，
 *   动画结束后才移除——不用 `Crossfade` 的双向渐隐，避免交叉溶解的"亮度凹陷"。
 * - [skipAnimation] 为 true 时立即绘制，不做动画。用于内存缓存命中：
 *   滚动回弹、翻页返回时封面必须瞬时出现，闪淡入反而像卡顿。
 *
 * 接入方：[JmCoverImage]（网络封面管线，按 DataSource 决定是否动画）、
 * [LocalCoverImage]（本地文件封面）。
 */
@Composable
internal fun CoverCrossfadeImage(
    painter: Painter?,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
    durationMillis: Int = 300,
    skipAnimation: Boolean = false,
    isActive: Boolean = LocalCoverWorkActive.current,
) {
    // A memory hit / detail handoff must draw in this frame, not after a LaunchedEffect.
    if (skipAnimation && painter != null) {
        Image(painter, contentDescription, modifier, contentScale = contentScale)
        return
    }
    var underPainter by remember { mutableStateOf<Painter?>(null) }
    var currentPainter by remember { mutableStateOf<Painter?>(null) }
    val fadeIn = remember { Animatable(1f) }

    LaunchedEffect(painter, skipAnimation, isActive) {
        when {
            painter == null -> {
                underPainter = null
                currentPainter = null
            }
            // Retained/offscreen pages settle once, without ticking an invisible animation or
            // replaying it when the user returns. Already displayed content stays in place.
            !isActive -> {
                underPainter = null
                currentPainter = painter
                fadeIn.snapTo(1f)
            }
            // A quick hide/show can cancel the hidden effect before it settles. Finish the
            // existing cover instead of leaving a cancelled fade at partial opacity.
            currentPainter == painter -> {
                underPainter = null
                fadeIn.snapTo(1f)
            }
            else -> {
                underPainter = currentPainter
                currentPainter = painter
                fadeIn.snapTo(0f)
                fadeIn.animateTo(1f, tween(durationMillis, easing = LinearOutSlowInEasing))
                if (currentPainter == painter) underPainter = null
            }
        }
    }

    Box(modifier) {
        if (underPainter == null && currentPainter == null) {
            Image(
                painter = CoverPlaceholderPainter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            underPainter?.let { under ->
                Image(
                    painter = under,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        currentPainter?.let { current ->
            Image(
                painter = current,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = fadeIn.value
                        // A BitmapPainter emits one image draw. Modulating that draw keeps the
                        // same fade without allocating a per-cover offscreen alpha buffer.
                        // Arbitrary/animated painters retain group alpha for overlapping content.
                        compositingStrategy = if (current is BitmapPainter) {
                            CompositingStrategy.ModulateAlpha
                        } else {
                            CompositingStrategy.Auto
                        }
                    },
            )
        }
    }
}

/**
 * 本地封面（已下载漫画的文件封面）淡入加载。内存缓存命中跳过动画，
 * 磁盘/首次解码淡入 300ms。网络封面一律走 [JmCoverImage]，不要用本组件。
 *
 * 请求必须带显式 [CoverSizeResolver]：裸 model 的 rememberAsyncImagePainter
 * 会挂起等待 drawSize（painter 未绘制前 drawSize 恒为空），造成"永不加载"死锁。
 */
@Composable
internal fun LocalCoverImage(
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
    onReady: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val active = LocalCoverWorkActive.current
    val visible = LocalCoverInViewport.current
    val scrolling = LocalComicGridScrolling.current()
    val workGate = remember(model) { CoverWorkGate(active, visible, scrolling) }
    SideEffect { workGate.setVisibility(active, visible, scrolling) }
    val sizeResolver = remember(workGate) { CoverSizeResolver(workGate) }
    var displayedPainter by remember(model) { mutableStateOf<Painter?>(null) }
    var skipFadeIn by remember(model) { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE")
    val requestPainter = rememberAsyncImagePainter(
        model = remember(context, model, sizeResolver) {
            ImageRequest.Builder(context)
                .data(model)
                .size(sizeResolver)
                .precision(Precision.INEXACT)
                .setParameter(CoverWorkGateParameter, workGate, memoryCacheKey = null)
                .build()
        },
        imageLoader = imageLoader ?: currentCoverImageLoader(),
        contentScale = contentScale,
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                skipFadeIn = state.result.dataSource.isMemorySource()
                displayedPainter = state.painter
            }
        },
    )
    SideEffect { onReady?.invoke(displayedPainter != null) }
    CoverCrossfadeImage(
        painter = displayedPainter,
        skipAnimation = onReady != null || skipFadeIn,
        contentDescription = contentDescription,
        contentScale = contentScale,
        // sizeResolver 挂在绘制节点上，测量时把布局约束喂给请求 size
        modifier = modifier.then(sizeResolver),
    )
}
