package com.par9uet.jm.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Frosted draws a translucent gradient without capturing or filtering the page behind it. */
enum class GlassBackdropMode {
    Blur,
    Frosted,
}

/**
 * Shared material constants for every glass consumer.
 *
 * The blur value is intentionally an experimental starting point. It is not an exact NagramX
 * constant: NagramX currently performs its glass blur in a larger downscaled pipeline.
 */
@Immutable
data class GlassMaterialStyle(
    val blurRadius: Dp = DefaultBlurRadius,
    val tintAlpha: Float = 0.76f,
    val borderWidth: Dp = 0.4.dp,
    val shadowRadius: Dp = 2.667.dp,
    val shadowDy: Dp = 0.85.dp,
) {
    companion object {
        /** 其它界面共用的高斯模糊半径。 */
        val DefaultBlurRadius: Dp = 18.dp

        /**
         * 阅读器内页的模糊档位。**当前与全局一致**（= [DefaultBlurRadius]）。
         *
         * 这个独立常量存在的理由：内页渲染压力大时容易触发动效退化——漫画页是全屏大位图 +
         * RenderEffect 的组合，GPU 余量本就紧张，一旦被吃光，系统随即降级动效（掉帧、模糊
         * 回退成纯色）。所以它是"只影响阅读器、可单独收紧"的旋钮，不跟全局绑死；
         * 历史上曾取 [DefaultBlurRadius] / 2。要回退成半强度档，只改这一行。
         */
        val ReaderBlurRadius: Dp = DefaultBlurRadius

        val Default = GlassMaterialStyle()

        val Reader = GlassMaterialStyle(blurRadius = ReaderBlurRadius)
    }
}

@Immutable
data class GlassSurfaceStyle(
    val cornerRadius: Dp = 28.dp,
    val material: GlassMaterialStyle = GlassMaterialStyle.Default,
) {
    companion object {
        val Default = GlassSurfaceStyle()

        /** 阅读器内页：模糊档位见 [GlassMaterialStyle.ReaderBlurRadius]（当前与全局一致），圆角与默认一致。 */
        val Reader = GlassSurfaceStyle(material = GlassMaterialStyle.Reader)

        fun reader(cornerRadius: Dp = 28.dp): GlassSurfaceStyle =
            GlassSurfaceStyle(cornerRadius = cornerRadius, material = GlassMaterialStyle.Reader)
    }
}

/**
 * Primary navigation geometry plus the shared material. Other consumers own their size and only
 * use [GlassSurfaceStyle]. The material accessors keep the accepted primary-bar values readable
 * at existing call sites.
 */
@Immutable
data class GlassStyle(
    val barHeight: Dp = 56.dp,
    val outerMargin: Dp = 8.dp,
    val maxBarWidth: Dp = 250.dp,
    val cornerRadius: Dp = 28.dp,
    val material: GlassMaterialStyle = GlassMaterialStyle.Default,
    val selectedIndicatorAlpha: Float = 0.09f,
) {
    val blurRadius: Dp get() = material.blurRadius
    val tintAlpha: Float get() = material.tintAlpha
    val borderWidth: Dp get() = material.borderWidth
    val shadowRadius: Dp get() = material.shadowRadius
    val shadowDy: Dp get() = material.shadowDy

    companion object {
        val Default = GlassStyle()
    }
}

object GlassCapabilities {
    const val NativeBackdropBlurApi = 31

    fun usesNativeBackdropBlur(apiLevel: Int): Boolean = apiLevel >= NativeBackdropBlurApi
}
