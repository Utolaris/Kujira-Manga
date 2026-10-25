package com.par9uet.jm.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.par9uet.jm.ui.glass.LocalGlassAppearance
import com.par9uet.jm.ui.glass.GlassMaterialStyle
import com.par9uet.jm.ui.glass.withAppearance
import com.par9uet.jm.ui.glass.GlassImageEffectCache
import androidx.compose.ui.platform.LocalDensity
import com.par9uet.jm.data.models.GlassEffect
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.ui.components.currentCoverImageLoader
import com.par9uet.jm.coil.jmCoverCacheKey
import org.koin.compose.getKoin

/**
 * 详情页封面与标签区的模糊封面背景（对齐上游 animeko SubjectBlurredBackground 的视觉方案）：
 *
 * 1. 封面图以 ContentScale.Crop 打满底（与 [JmCoverImage] 共享同一 jmCoverCacheKey
 *    缓存，主封面加载完成后背景零网络开销）；
 * 2. `Modifier.blur` 高斯模糊（RenderEffect，API 31+；API 30 上不绘制图片，
 *    退化为纯色渐变——锐利放大图垫在文字下不可接受）；
 * 3. 表面色纵向渐变罩压住图片：顶部 63% alpha 的 surface 恒定到 40% 高度，
 *    再平滑渐变到纯 background；末尾 5% 与评论区底色一致，罩层不参与模糊。
 *
 * 用法：放在详情页封面与标签的共同 Box 底层（`Modifier.matchParentSize()`），随 header
 * 一起滚动；渐变在标签末尾收束，评论区域保持页面纯色背景。
 */
@Composable
internal fun CoverBlurredBackground(
    comicId: Int,
    remoteHost: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 24.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    imageAspectRatio: Float? = null,
) {
    val resolver: CoverImageHostResolver = getKoin().get()
    val coverUrl = remember(comicId, remoteHost) {
        resolver.coverUrls(comicId, remoteHost).firstOrNull()
    }
    val context = LocalContext.current
    val cacheKey = remember(comicId) { jmCoverCacheKey(comicId) }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val appearance = LocalGlassAppearance.current
    val density = LocalDensity.current.density
    val softMaterial = remember(appearance, density) { GlassMaterialStyle().withAppearance(appearance, density) }
    val dark = backgroundColor.luminance() < 0.5f
    val effectCache = remember(softMaterial, dark, density) {
        if (Build.VERSION.SDK_INT >= 31) GlassImageEffectCache(softMaterial, dark, density) else null
    }

    val coverPainter: Painter? = if (coverUrl != null && blurSupported) {
        val imageLoader = currentCoverImageLoader()
        val request = remember(context, coverUrl, cacheKey) {
            ImageRequest.Builder(context)
                .data(coverUrl)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .build()
        }
        rememberAsyncImagePainter(
            model = request,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
        )
    } else {
        null
    }

    val imageEffect = when {
        !blurSupported -> Modifier
        appearance.effect == GlassEffect.Soft -> Modifier.graphicsLayer {
            renderEffect = effectCache?.effectFor(size.width, size.height)?.asComposeRenderEffect()
        }
        else -> Modifier.blur(blurRadius)
    }
    CoverBackdropLayers(coverPainter, imageEffect, backgroundColor, surfaceColor, modifier, imageAspectRatio)
}

/** The final gradient is deliberately outside the filtered image so its end matches the page. */
@Composable
internal fun CoverBackdropLayers(
    painter: Painter?,
    imageEffect: Modifier,
    backgroundColor: Color,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    imageAspectRatio: Float? = null,
) {
    val scrimColor = if (backgroundColor.luminance() < 0.5f) {
        surfaceColor.copy(alpha = 0xA2.toFloat() / 0xFF)
    } else {
        Color(0xA2FAFAFA)
    }
    Box(modifier.clipToBounds()) {
        if (painter != null) {
            // Report the growing backdrop bounds to the parent, but paint the image at a fixed
            // width-derived size. A tag-height change must not recenter or rescale Crop.
            val imageSizing = if (imageAspectRatio != null) Modifier.layout { measurable, constraints ->
                val width = constraints.maxWidth
                val imageHeight = (width / imageAspectRatio).roundToInt().coerceAtLeast(1)
                val image = measurable.measure(Constraints.fixed(width, imageHeight))
                layout(width, constraints.maxHeight) { image.place(0, 0) }
            } else Modifier
            Box(Modifier.matchParentSize().then(imageSizing).then(imageEffect)
                .paint(painter, sizeToIntrinsics = false, contentScale = ContentScale.Crop))
        }
        Box(Modifier.matchParentSize().drawWithCache {
            val imageHeight = imageAspectRatio?.let { size.width / it } ?: size.height
            val scrim = Brush.verticalGradient(
                0f to scrimColor,
                0.4f to scrimColor,
                0.95f to backgroundColor,
                1f to backgroundColor,
                endY = min(size.height, imageHeight).coerceAtLeast(1f),
            )
            onDrawBehind { drawRect(scrim) }
        })
    }
}
