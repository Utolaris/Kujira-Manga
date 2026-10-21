package com.par9uet.jm.reader.coordinator

import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.reader.ReaderImageException
import com.par9uet.jm.reader.ReaderImageHostManager
import com.par9uet.jm.reader.ReaderRemoteCandidate
import com.par9uet.jm.reader.ReaderRemoteObserver

/** L2 maps remote events into shared host-health state for CDN selection. */
internal class ReaderRemoteTelemetry(
    private val imageHostManager: ReaderImageHostManager,
) : ReaderRemoteObserver {
    override fun onResponseHeaders(
        url: String,
        candidate: ReaderRemoteCandidate,
        elapsedMillis: Long,
        httpCode: Int,
    ) {
        if (httpCode in 200..299) {
            // Host ranking uses TTFB only; body transfer and decode speed are page-specific.
            imageHostManager.recordLatencySample(url, elapsedMillis)
        }
    }

    override fun onRequestFailed(
        url: String,
        candidate: ReaderRemoteCandidate,
        error: Throwable?,
    ) {
        // Resource failures stay local to this page; only network/host failures cool a CDN.
        val failureKind = when {
            error is ReaderImageException && error.httpCode == null ->
                ImageHostFailureKind.RESOURCE_FAILURE
            else -> classifyImageHostFailure(
                error,
                httpCodeHint = (error as? ReaderImageException)?.httpCode,
            )
        }
        if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
            imageHostManager.recordHostFailure(url)
        }
    }
}
