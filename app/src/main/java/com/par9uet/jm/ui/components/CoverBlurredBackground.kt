package com.par9uet.jm.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.currentCoverImageLoader
import com.par9uet.jm.coil.jmCoverCacheKey
import org.koin.compose.getKoin

/**
 * 详情页头部的模糊封面背景（对齐上游 animeko SubjectBlurredBackground 的视觉方案）：
 *
 * 1. 封面图以 ContentScale.Crop 打满底（与 [JmCoverImage] 共享同一 jmCoverCacheKey
 *    缓存，主封面加载完成后背景零网络开销）；
 * 2. `Modifier.blur` 高斯模糊（RenderEffect，API 31+；API 30 上不绘制图片，
 *    退化为纯色渐变——锐利放大图垫在文字下不可接受）；
 * 3. 表面色纵向渐变罩压住图片：顶部 63% alpha 的 surface 恒定到 40% 高度，
 *    再渐变到纯 background——背景下半部分视觉上就是页面纯色，内容区可读。
 *
 * 用法：放在详情页 header Box 的底层（`Modifier.matchParentSize()`），随 header
 * 一起滚动；header 之外的内容区保持页面纯色背景。
 */
@Composable
internal fun CoverBlurredBackground(
    comicId: Int,
    remoteHost: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 24.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
) {
    val resolver: CoverImageHostResolver = getKoin().get()
    val coverUrl = remember(comicId, remoteHost) {
        resolver.coverUrls(comicId, remoteHost).firstOrNull()
    }
    val context = LocalContext.current
    val cacheKey = remember(comicId) { jmCoverCacheKey(comicId) }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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

    // 表面色罩: 暗色主题用 surface, 亮色主题用固定浅灰 (对齐 animeko 的取色策略).
    val scrimColor = if (backgroundColor.luminance() < 0.5f) {
        surfaceColor.copy(alpha = 0xA2.toFloat() / 0xFF)
    } else {
        Color(0xA2FAFAFA)
    }
    val scrimBrush = Brush.verticalGradient(
        0f to scrimColor,
        0.4f to scrimColor,
        1f to backgroundColor,
    )

    Box(
        modifier
            .then(if (blurSupported) Modifier.blur(blurRadius) else Modifier)
            .then(
                if (coverPainter != null) {
                    Modifier.paint(coverPainter, contentScale = ContentScale.Crop)
                } else {
                    Modifier
                },
            )
            .background(scrimBrush),
    )
}
