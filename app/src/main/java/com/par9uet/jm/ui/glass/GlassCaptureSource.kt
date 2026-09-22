package com.par9uet.jm.ui.glass

import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.annotation.RequiresApi
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError

/**
 * Records the one logical source composition shared by every glass surface in a host. It stores
 * only a GPU display list; no bitmap or pixel buffer is allocated.
 *
 * Failure policy: a failed record must NOT latch. Recording happens on the render thread against
 * GPU resources that the reader (large bitmaps under a RenderEffect) can transiently exhaust, and
 * a single failure used to disable blur for the whole host until the screen was recreated — that is
 * exactly the "面板偶尔变成纯色、过一会儿又好了" symptom. Instead the failure is retried after a
 * short backoff, so the glass heals itself.
 */
internal class GlassCaptureSource(
    private val sourceView: View,
    private val onCaptureStart: () -> Unit,
    private val onCaptureEnd: () -> Unit,
) {
    private var dirty = true
    private var generationValue = 0
    private var renderNodeValue: RenderNode? = null
    private var failureStreak = 0
    private var retryNotBeforeUptimeMs = 0L
    private var reportedDegraded = false
    // [GlassDiag] 临时诊断：跳过录制的日志节流时间戳。
    private var lastSkipLogUptimeMs = 0L

    @get:RequiresApi(31)
    val renderNode: RenderNode
        get() = renderNodeValue ?: RenderNode("JmGlassSharedSource").also {
            it.setClipToBounds(true)
            renderNodeValue = it
        }

    val generation: Int get() = generationValue

    // [GlassDiag] 临时诊断用。
    internal val isDirtyForDiagnostics: Boolean get() = dirty

    // [GlassDiag] 临时诊断：自行做版本判断，避免调用点需要 @RequiresApi。
    internal fun sharedHasDisplayList(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) renderNode.hasDisplayList() else false

    /**
     * False while a failed record is waiting out its backoff. Surfaces must then fall back to the
     * plain tint instead of blurring a stale display list.
     */
    val nativeCaptureAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SystemClock.uptimeMillis() >= retryNotBeforeUptimeMs

    fun markDirty(reason: String = "") {
        // [GlassDiag] 临时诊断：只在 false→true 跳变时记一行，便于看出"谁要求重录"。
        if (!dirty) {
            log("GlassDiag", "[capture] 需要重录 ← $reason")
        }
        dirty = true
    }

    @RequiresApi(31)
    fun recordIfNeeded() {
        if (!nativeCaptureAvailable || !dirty || sourceView.width <= 0 || sourceView.height <= 0) {
            // [GlassDiag] 临时诊断：跳过录制的原因（500ms 节流，避免逐帧刷屏）。
            val now = SystemClock.uptimeMillis()
            if (now - lastSkipLogUptimeMs >= 500L) {
                lastSkipLogUptimeMs = now
                log(
                    "GlassDiag",
                    "[capture] 跳过录制：available=$nativeCaptureAvailable dirty=$dirty " +
                        "sourceSize=${sourceView.width}x${sourceView.height} " +
                        "gen=$generationValue 退避剩余=${(retryNotBeforeUptimeMs - now).coerceAtLeast(0)}ms",
                )
            }
            return
        }

        try {
            renderNode.setPosition(0, 0, sourceView.width, sourceView.height)
            val recordingCanvas = renderNode.beginRecording(sourceView.width, sourceView.height)
            onCaptureStart()
            try {
                sourceView.draw(recordingCanvas)
            } finally {
                onCaptureEnd()
                renderNode.endRecording()
            }
            dirty = false
            generationValue++
            // [GlassDiag] 临时诊断：录制成功后的关键事实——共享底图此刻是否有真实显示列表。
            log(
                "GlassDiag",
                "[capture] 录制成功 gen=$generationValue size=${sourceView.width}x" +
                    "${sourceView.height} sharedHasDisplayList=${renderNode.hasDisplayList()}",
            )
            if (failureStreak > 0) {
                failureStreak = 0
                if (reportedDegraded) {
                    reportedDegraded = false
                    log("GlassCaptureSource", "共享底图录制恢复，玻璃背板重新走模糊")
                }
            }
        } catch (_: RuntimeException) {
            failureStreak++
            val backoffMs = (RetryBackoffStepMs * failureStreak).coerceAtMost(RetryBackoffMaxMs)
            retryNotBeforeUptimeMs = SystemClock.uptimeMillis() + backoffMs
            // Keep dirty: the retry has to actually re-record, not skip to the stale node.
            dirty = true
            if (!reportedDegraded) {
                reportedDegraded = true
                logError(
                    "GlassCaptureSource",
                    "共享底图录制失败（第 ${failureStreak} 次）：${backoffMs}ms 后重试，" +
                        "退避窗口内玻璃面板退回纯色（不再永久降级）",
                )
            }
        }
    }

    private companion object {
        const val RetryBackoffStepMs = 250L
        const val RetryBackoffMaxMs = 2_000L
    }
}
