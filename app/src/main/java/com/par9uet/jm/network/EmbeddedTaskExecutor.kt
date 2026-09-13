package com.par9uet.jm.network

import io.github.jukomu.jmcomic.api.exception.JmComicException
import java.util.concurrent.ExecutorService

/**
 * The SDK starts initialization with execute() in its constructor and rethrows API failures.
 * Those exceptions escape the repository's request coroutine and kill Android's process.
 * Contain SDK API failures at that task boundary; submitted futures still report their errors
 * to callers, and unrelated programming errors retain their normal exception handling.
 */
internal class EmbeddedTaskExecutor(
    private val delegate: ExecutorService,
    private val onFailure: (JmComicException) -> Unit,
) : ExecutorService by delegate {
    override fun execute(command: Runnable) {
        delegate.execute {
            try {
                command.run()
            } catch (error: JmComicException) {
                onFailure(error)
            }
        }
    }
}
