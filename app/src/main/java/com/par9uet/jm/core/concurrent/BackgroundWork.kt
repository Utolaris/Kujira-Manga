package com.par9uet.jm.core.concurrent

import android.os.Looper
import android.os.Process
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext

/** One shared execution budget for startup and account maintenance, including nested IO calls. */
class BackgroundWork(
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    private val priority: CoroutineContext = BackgroundThreadPriority(),
) : AbstractCoroutineContextElement(Key) {
    val context: CoroutineContext get() = this + dispatcher + priority
    companion object Key : CoroutineContext.Key<BackgroundWork>
}

/** Foreground calls keep their normal IO dispatcher; background calls retain their shared budget. */
suspend fun backgroundIoDispatcher(): CoroutineDispatcher =
    currentCoroutineContext()[BackgroundWork]?.dispatcher ?: Dispatchers.IO

/** Priority follows resumed work across dispatcher switches and is restored before thread reuse. */
internal class BackgroundThreadPriority(
    private val read: () -> Int? = {
        if (Looper.myLooper() == Looper.getMainLooper()) null
        else Process.getThreadPriority(Process.myTid())
    },
    private val write: (Int) -> Unit = Process::setThreadPriority,
) : ThreadContextElement<Int?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BackgroundThreadPriority>

    override fun updateThreadContext(context: CoroutineContext): Int? = runCatching {
        val previous = read() ?: return null
        if (previous >= Process.THREAD_PRIORITY_BACKGROUND) return null
        write(Process.THREAD_PRIORITY_BACKGROUND)
        previous
    }.getOrNull()

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int?) {
        if (oldState != null) runCatching { write(oldState) }
    }
}
