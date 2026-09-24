package com.par9uet.jm.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class GlassSurfaceColors(
    val tint: Int,
    val topStroke: Int,
    val bottomStroke: Int,
    val shadow: Int,
)

/**
 * Draws one native glass surface from the shared source display list. The sharp foreground is
 * rendered by the sibling overlay ComposeView and is therefore never part of this view's source.
 */
@SuppressLint("ViewConstructor")
internal class GlassBackdropView(
    context: Context,
    private var style: GlassSurfaceStyle = GlassSurfaceStyle.Default,
    private val surfaceId: String = "glass-surface",
    private var backdropMode: GlassBackdropMode = GlassBackdropMode.Blur,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val surfaceRect = RectF()
    private val glassPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frostedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bottomStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var colors = GlassSurfaceColors(
        tint = Color.TRANSPARENT,
        topStroke = Color.TRANSPARENT,
        bottomStroke = Color.TRANSPARENT,
        shadow = Color.TRANSPARENT,
    )
    private var source: GlassCaptureSource? = null
    private var sourceView: View? = null
    private var lastSourceGeneration = -1
    private var sourceRegionDirty = true
    private var geometryDirty = true
    private var nativeRenderState: NativeGlassRenderState? = createNativeRenderState(style)
    private var nativeFailureStreak = 0
    private var nativeRetryNotBeforeUptimeMs = 0L
    private var reportedDegraded = false
    /** 最近一次确认"共享底图有内容"的 gen；用于识别它在同一 gen 内被清空。 */
    private var contentSeenGeneration = -1

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        shadowPaint.style = Paint.Style.FILL
        tintPaint.style = Paint.Style.FILL
        updatePaintMetrics()
    }

    fun setSurfaceStyle(newStyle: GlassSurfaceStyle) {
        if (style == newStyle) return
        style = newStyle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nativeRenderState?.setBlurRadius(style.material.blurRadiusPx())
        }
        updatePaintMetrics()
        sourceRegionDirty = true
        geometryDirty = true
        invalidate()
    }

    fun setBackdropMode(mode: GlassBackdropMode) {
        if (backdropMode == mode) return
        backdropMode = mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nativeRenderState?.renderNode?.discardDisplayList()
        }
        nativeRenderState = createNativeRenderState(style)
        nativeFailureStreak = 0
        nativeRetryNotBeforeUptimeMs = 0L
        reportedDegraded = false
        sourceRegionDirty = true
        geometryDirty = true
        invalidate()
    }

    fun setSource(newSource: GlassCaptureSource, newSourceView: View) {
        source = newSource
        sourceView = newSourceView
        lastSourceGeneration = -1
        sourceRegionDirty = true
        invalidate()
    }

    fun markSurfacePositionChanged() {
        sourceRegionDirty = true
        invalidate()
    }

    fun setColors(newColors: GlassSurfaceColors) {
        if (colors == newColors) return
        colors = newColors
        geometryDirty = true
        tintPaint.color = colors.tint
        topStrokePaint.color = colors.topStroke
        bottomStrokePaint.color = colors.bottomStroke
        shadowPaint.color = Color.argb(1, 0, 0, 0)
        updatePaintMetrics()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometryDirty = true
        sourceRegionDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGeometryIfNeeded()
        if (surfaceRect.isEmpty) return

        canvas.drawRoundRect(surfaceRect, cornerRadiusPx(), cornerRadiusPx(), shadowPaint)

        val sharedSource = source
        val canAttempt = canAttemptNativeRender()
        val sourceAvailable = sharedSource?.nativeCaptureAvailable == true
        val hardwareAccelerated = canvas.isHardwareAccelerated
        // 共享底图的显示列表可能在没有重录的情况下变为空（实测：gen 不变、dirty=false，
        // 而 sharedHasDisplayList 从 true 变 false）。此时录出来的区域是一片空气——
        // 画面上就是"面板有底色、没有高斯模糊"，而且不会有任何失败日志。
        //
        // 只在"同一个 gen 曾经有内容、现在变空"这种**跳变**上请求一次重录：
        // 不去门控本帧的录制/绘制（万一某些设备上该信号偏差就会把偶发降级做成永久降级），
        // 也不会逐帧抖动（请求一次即 gen 前进，之后就没人再问，静态源照样收敛）。
        val sourceHasContent = sharedSource?.sharedHasDisplayList() == true
        if (canAttempt && sharedSource != null && hardwareAccelerated) {
            val sourceGeneration = sharedSource.generation
            if (sourceHasContent) {
                contentSeenGeneration = sourceGeneration
            } else if (contentSeenGeneration == sourceGeneration) {
                contentSeenGeneration = -1
                sharedSource.markDirty()
            }
        }
        if (
            canAttempt &&
            sharedSource != null &&
            sharedSource.nativeCaptureAvailable &&
            hardwareAccelerated &&
            (sourceRegionDirty || lastSourceGeneration != sharedSource.generation)
        ) {
            recordSourceRegion(sharedSource)
        }

        if (
            canAttempt &&
            sharedSource != null &&
            sourceAvailable &&
            hardwareAccelerated &&
            !sourceRegionDirty &&
            lastSourceGeneration == sharedSource.generation
        ) {
            drawNativeBackdrop(canvas)
        }

        // On API 30 this translucent tint is the complete fallback. On API 31+ it is drawn over
        // the blurred source RenderNode, keeping the same material geometry on every device.
        canvas.drawRoundRect(
            surfaceRect,
            cornerRadiusPx(),
            cornerRadiusPx(),
            if (backdropMode == GlassBackdropMode.Frosted) frostedPaint else tintPaint,
        )
        drawDirectionalStroke(canvas, topStrokePaint, clipTop = true)
        drawDirectionalStroke(canvas, bottomStrokePaint, clipTop = false)
    }

    /** 原生模糊当前是否可尝试：退避窗口内直接跳过，避免每帧都撞同一个 GPU 失败。 */
    private fun canAttemptNativeRender(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            nativeRenderState != null &&
            SystemClock.uptimeMillis() >= nativeRetryNotBeforeUptimeMs

    private fun updateGeometryIfNeeded() {
        if (!geometryDirty) return

        surfaceRect.set(0f, 0f, width.toFloat(), height.toFloat())
        glassPath.rewind()
        glassPath.addRoundRect(
            surfaceRect,
            cornerRadiusPx(),
            cornerRadiusPx(),
            Path.Direction.CW,
        )
        if (backdropMode == GlassBackdropMode.Frosted && !surfaceRect.isEmpty) {
            // Dense enough for text over detailed manga, with a soft diagonal light falloff.
            // Only a small gradient is drawn; no source bitmap or RenderEffect is needed.
            val rgb = colors.tint and 0x00FFFFFF
            frostedPaint.shader = LinearGradient(
                0f, 0f, surfaceRect.width(), surfaceRect.height(),
                intArrayOf((0xF2 shl 24) or rgb, (0xDB shl 24) or rgb, (0xED shl 24) or rgb),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        geometryDirty = false
    }

    @RequiresApi(31)
    private fun recordSourceRegion(sharedSource: GlassCaptureSource) {
        val sourceView = sourceView ?: return
        if (sourceView.width <= 0 || sourceView.height <= 0) return

        val padding = style.material.blurRadiusPx()
        val captureLeftInSurface = -padding
        val captureTopInSurface = -padding
        val captureWidth = max(1, ceil(surfaceRect.width() + padding * 2f).toInt())
        val captureHeight = max(1, ceil(surfaceRect.height() + padding * 2f).toInt())

        val sourceLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        sourceView.getLocationInWindow(sourceLocation)
        getLocationInWindow(surfaceLocation)
        val captureLeftInSource =
            surfaceLocation[0] + captureLeftInSurface - sourceLocation[0]
        val captureTopInSource =
            surfaceLocation[1] + captureTopInSurface - sourceLocation[1]

        try {
            nativeRenderState?.record(
                width = captureWidth,
                height = captureHeight,
                sourceLeft = captureLeftInSource,
                sourceTop = captureTopInSource,
                sourceNode = sharedSource.renderNode,
            )
            lastSourceGeneration = sharedSource.generation
            sourceRegionDirty = false
            if (nativeFailureStreak > 0) {
                nativeFailureStreak = 0
                if (reportedDegraded) {
                    reportedDegraded = false
                    log("GlassBackdropView", "[$surfaceId] 原生模糊恢复")
                }
            }
        } catch (_: RuntimeException) {
            // A transient GPU failure (heavy reader bitmaps under RenderEffect) must not degrade
            // this surface to translucent tint until it is destroyed — that is the "面板偶尔变
            // 成纯色、过一会儿又好了" symptom. Back off, rebuild the RenderNode and keep retrying;
            // a successful record (or a recreated view when the bar is shown again) clears it.
            nativeFailureStreak++
            val backoffMs = (RetryBackoffStepMs * nativeFailureStreak).coerceAtMost(RetryBackoffMaxMs)
            nativeRetryNotBeforeUptimeMs = SystemClock.uptimeMillis() + backoffMs
            nativeRenderState = createNativeRenderState(style)
            sourceRegionDirty = true
            if (!reportedDegraded) {
                reportedDegraded = true
                logError(
                    "GlassBackdropView",
                    "[$surfaceId] 原生模糊失败（第 ${nativeFailureStreak} 次）：${backoffMs}ms 后重试，" +
                        "退避窗口内退化为纯色（不再永久降级）",
                )
            }
        }
    }

    @RequiresApi(31)
    private fun drawNativeBackdrop(canvas: Canvas) {
        val nativeState = nativeRenderState ?: return
        val padding = style.material.blurRadiusPx()
        canvas.withSave {
            clipPath(glassPath)
            translate(-padding, -padding)
            drawRenderNode(nativeState.renderNode)
        }
    }

    private fun drawDirectionalStroke(canvas: Canvas, paint: Paint, clipTop: Boolean) {
        val split = surfaceRect.top + surfaceRect.height() / 2f
        if (clipTop) {
            canvas.withClip(surfaceRect.left, surfaceRect.top, surfaceRect.right, split) {
                drawRoundRect(surfaceRect, cornerRadiusPx(), cornerRadiusPx(), paint)
            }
        } else {
            canvas.withClip(surfaceRect.left, split, surfaceRect.right, surfaceRect.bottom) {
                drawRoundRect(surfaceRect, cornerRadiusPx(), cornerRadiusPx(), paint)
            }
        }
    }

    private fun updatePaintMetrics() {
        val material = style.material
        val strokeWidth = material.borderWidth.value * density
        topStrokePaint.strokeWidth = strokeWidth
        bottomStrokePaint.strokeWidth = strokeWidth
        shadowPaint.setShadowLayer(
            material.shadowRadius.value * density,
            0f,
            material.shadowDy.value * density,
            colors.shadow,
        )
    }

    private fun cornerRadiusPx(): Float = min(
        style.cornerRadius.value * density,
        surfaceRect.height() / 2f,
    )

    private fun GlassMaterialStyle.blurRadiusPx(): Float = blurRadius.value * density

    @RequiresApi(31)
    private class NativeGlassRenderState(initialBlurRadius: Float) {
        val renderNode = android.graphics.RenderNode("JmGlassSurface")
        private var blurRadius = 0f

        init {
            renderNode.setClipToBounds(true)
            setBlurRadius(initialBlurRadius)
        }

        fun setBlurRadius(radius: Float) {
            if (blurRadius == radius) return
            blurRadius = radius
            renderNode.setRenderEffect(
                if (radius > 0f) {
                    android.graphics.RenderEffect.createBlurEffect(
                        radius,
                        radius,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    null
                },
            )
        }

        fun record(
            width: Int,
            height: Int,
            sourceLeft: Float,
            sourceTop: Float,
            sourceNode: android.graphics.RenderNode,
        ) {
            renderNode.setPosition(0, 0, width, height)
            val recordingCanvas = renderNode.beginRecording(width, height)
            recordingCanvas.withTranslation(-sourceLeft, -sourceTop) {
                drawRenderNode(sourceNode)
            }
            renderNode.endRecording()
        }
    }

    private fun createNativeRenderState(style: GlassSurfaceStyle): NativeGlassRenderState? {
        return if (
            backdropMode == GlassBackdropMode.Blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            NativeGlassRenderState(style.material.blurRadius.value * density)
        } else {
            null
        }
    }

    private companion object {
        const val RetryBackoffStepMs = 250L
        const val RetryBackoffMaxMs = 2_000L
    }
}
