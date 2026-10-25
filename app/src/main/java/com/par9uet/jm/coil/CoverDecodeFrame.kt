package com.par9uet.jm.coil

import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Release one decoded cover per display frame instead of uploading a whole cold row together. */
internal suspend fun awaitCoverDecodeFrame() = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { continuation.resume(Unit) }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }
}
