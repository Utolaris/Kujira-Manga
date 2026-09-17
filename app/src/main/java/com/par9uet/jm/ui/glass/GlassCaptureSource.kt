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

    @get:RequiresApi(31)
    val renderNode: RenderNode
        get() = renderNodeValue ?: RenderNode("JmGlassSharedSource").also {
            it.setClipToBounds(true)
            renderNodeValue = it
        }

    val generation: Int get() = generationValue

    /**
     * False while a failed record is waiting out its backoff. Surfaces must then fall back to the
     * plain tint instead of blurring a stale display list.
     */
    val nativeCaptureAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SystemClock.uptimeMillis() >= retryNotBeforeUptimeMs

    fun markDirty() {
        dirty = true
    }

    @RequiresApi(31)
    fun recordIfNeeded() {
        if (!nativeCaptureAvailable || !dirty || sourceView.width <= 0 || sourceView.height <= 0) {
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
