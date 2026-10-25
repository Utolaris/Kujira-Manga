package com.par9uet.jm.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.par9uet.jm.data.models.GlassEffect

/** Adapted material recipe: blur size, modest color lift, fixed translucency and a fine light grain.
 * Design reference: Hyper-PiliPlus GlassMaterialModifier / AdvancedMaterialTuning (GPL-3.0).
 * This implementation uses our shared RenderNode source rather than a second capture library.
 */
internal data class SoftGlassProfile(
    val saturation: Float,
    val contrast: Float,
    val brightness: Float,
    val tintAlpha: Float,
    val grain: Float,
)

private val saturationAnchors = listOf(16f to 1.05f, 34f to 1.02f, 52f to 1f, 72f to 0.98f, 92f to 0.95f)

internal fun softGlassProfile(level: Int, dark: Boolean): SoftGlassProfile {
    val radius = level.coerceIn(16, 168)
    val anchors = saturationAnchors
    val upper = anchors.indexOfFirst { radius <= it.first }
    val saturation = when {
        upper < 0 -> anchors.last().second
        upper == 0 -> anchors.first().second
        else -> {
            val (lowRadius, lowValue) = anchors[upper - 1]
            val (highRadius, highValue) = anchors[upper]
            lowValue + (highValue - lowValue) * (radius - lowRadius) / (highRadius - lowRadius)
        }
    }
    return SoftGlassProfile(saturation * 1.096648f, if (dark) 0.96f else 0.98f,
        if (dark) 0.025f else 0.015f, if (dark) 0.64f else 0.60f, 0.025f)
}

/** Shared by native glass surfaces and cover-image backgrounds. Foreground text is never filtered. */
@RequiresApi(31)
internal fun createGlassRenderEffect(
    material: GlassMaterialStyle,
    dark: Boolean,
    density: Float,
    width: Float,
    height: Float,
    padding: Float = 0f,
    cornerRadius: Float = 0f,
): RenderEffect? {
    val radius = material.blurRadius.value * density
    if (material.effect == GlassEffect.Gaussian) {
        return if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
    }
    val profile = softGlassProfile(material.softLevel, dark)
    val colors = ColorMatrix().apply { setSaturation(profile.saturation) }
    val c = profile.contrast
    val lift = (0.5f * (1f - c) + profile.brightness) * 255f
    colors.postConcat(ColorMatrix(floatArrayOf(
        c, 0f, 0f, 0f, lift, 0f, c, 0f, 0f, lift,
        0f, 0f, c, 0f, lift, 0f, 0f, 0f, 1f, 0f,
    )))
    val colorEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colors))
    val blurred = if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, colorEffect, Shader.TileMode.CLAMP) else colorEffect
    return if (Build.VERSION.SDK_INT >= 33 && width > padding * 2 && height > padding * 2) {
        RenderEffect.createChainEffect(SoftGlassOptics.create(width, height, padding, cornerRadius, density, profile.grain), blurred)
    } else blurred
}

/** Stable, surface-local grain avoids shimmer; refraction stays in the padded source region. */
@RequiresApi(33)
private object SoftGlassOptics {
    fun create(width: Float, height: Float, padding: Float, corner: Float, density: Float, grain: Float): RenderEffect {
        val shader = android.graphics.RuntimeShader("""
            uniform shader content;
            uniform float2 extent;
            uniform float inset;
            uniform float corner;
            uniform float pixelScale;
            uniform float grain;
            half4 main(float2 p) {
                float2 center = extent * 0.5;
                float2 halfSize = max(center - inset, float2(1.0));
                float r = min(corner, min(halfSize.x, halfSize.y));
                float2 q = abs(p - center) - halfSize + r;
                float edgeDistance = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
                float edge = 1.0 - smoothstep(0.0, 5.0 * pixelScale, abs(edgeDistance));
                float2 direction = (p - center) / max(length(p - center), 1.0);
                half4 sampleColor = content.eval(p - direction * edge * 1.4 * pixelScale);
                float noise = fract(sin(dot(floor(p / pixelScale), float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                float3 rgb = clamp(float3(sampleColor.rgb) + noise * grain * sampleColor.a, 0.0, float(sampleColor.a));
                return half4(rgb, sampleColor.a);
            }
        """.trimIndent())
        shader.setFloatUniform("extent", width, height)
        shader.setFloatUniform("inset", padding)
        shader.setFloatUniform("corner", corner)
        shader.setFloatUniform("pixelScale", density)
        shader.setFloatUniform("grain", grain)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

/** Cover layers may redraw during scroll; keep shader compilation tied to material/size changes. */
@RequiresApi(31)
internal class GlassImageEffectCache(
    private val material: GlassMaterialStyle,
    private val dark: Boolean,
    private val density: Float,
) {
    private var width = -1f
    private var height = -1f
    private var effect: RenderEffect? = null

    fun effectFor(width: Float, height: Float): RenderEffect? {
        if (this.width != width || this.height != height) {
            effect = createGlassRenderEffect(material, dark, density, width, height)
            this.width = width
            this.height = height
        }
        return effect
    }
}
