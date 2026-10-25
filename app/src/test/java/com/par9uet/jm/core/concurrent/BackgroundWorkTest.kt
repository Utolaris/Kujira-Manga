package com.par9uet.jm.core.concurrent

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkTest {
    @Test fun `priority follows dispatcher switches and is restored after failure`() = runBlocking {
        val priority = ThreadLocal.withInitial { 0 }
        val element = BackgroundThreadPriority(read = { priority.get() }, write = priority::set)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { first ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { second ->
                val work = BackgroundWork(first, element)
                val expected = IllegalStateException("request failed")
                try {
                    withContext(work.context) {
                        assertEquals(10, priority.get())
                        withContext(second) {
                            assertEquals(10, priority.get())
                            assertSame(first, backgroundIoDispatcher())
                            throw expected
                        }
                    }
                    fail("Expected request failure")
                } catch (actual: IllegalStateException) {
                    assertEquals(expected.message, actual.message)
                }
                assertEquals(0, withContext(first) { priority.get() })
                assertEquals(0, withContext(second) { priority.get() })
            }
        }
    }

    @Test fun `excluded UI thread and already lower priority threads remain untouched`() {
        val writes = mutableListOf<Int>()
        for (current in listOf(null, 10, 15)) {
            val context = BackgroundThreadPriority(read = { current }, write = { writes += it })
            val old = context.updateThreadContext(EmptyCoroutineContext)
            context.restoreThreadContext(EmptyCoroutineContext, old)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `foreground IO uses the normal dispatcher`() = runBlocking {
        assertSame(Dispatchers.IO, backgroundIoDispatcher())
    }

    @Test fun `nested IO work shares a two worker execution budget`() = runBlocking {
        val work = BackgroundWork(Dispatchers.IO.limitedParallelism(2), EmptyCoroutineContext)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = CountDownLatch(2)
        val overflow = CountDownLatch(1)
        val release = CountDownLatch(1)
        withContext(work.context) {
            val jobs = List(4) {
                async(backgroundIoDispatcher()) {
                    val running = active.incrementAndGet()
                    peak.accumulateAndGet(running, ::maxOf)
                    started.countDown()
                    if (running > 2) overflow.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS)) { "Workers were not released" }
                    } finally { active.decrementAndGet() }
                }
            }
            withContext(Dispatchers.Default) {
                try {
                    assertTrue("Both workers must run concurrently", started.await(5, TimeUnit.SECONDS))
                    assertFalse("A third worker exceeded the budget", overflow.await(200, TimeUnit.MILLISECONDS))
                } finally {
                    release.countDown()
                }
            }
            jobs.awaitAll()
        }
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }
}
