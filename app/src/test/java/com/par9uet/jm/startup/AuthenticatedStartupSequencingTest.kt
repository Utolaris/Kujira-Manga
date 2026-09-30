package com.par9uet.jm.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Collections.synchronizedList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the DoH-before-network waterfall and branch independence at startup. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticatedStartupSequencingTest {

    private class Trace {
        val events = synchronizedList(mutableListOf<String>())
        fun add(event: String) = events.add(event)
    }

    @Test
    fun `DoH init completes before remote config and user verification start`() = runTest {
        val trace = Trace()
        val dohReady = CompletableDeferred<Unit>()
        val startup = launch {
            runAuthenticatedStartupTasks(
                initDoh = {
                    trace.add("doh:start")
                    dohReady.await()
                    trace.add("doh:end")
                },
                refreshRemoteConfig = { trace.add("remote") },
                verifyUser = { trace.add("user") },
            )
        }
        runCurrent()
        assertEquals(listOf("doh:start"), trace.events)
        assertTrue(startup.isActive)
        dohReady.complete(Unit)
        startup.join()

        assertEquals(listOf("doh:start", "doh:end"), trace.events.take(2))
        assertTrue(trace.events.drop(2).containsAll(listOf("remote", "user")))
    }


    @Test
    fun `slow remote config does not block user verification`() = runTest {
        val trace = Trace()
        val userDone = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        val watcher = launch {
            userDone.await()          // wait until user verification observed
            assertEquals(listOf("doh", "remote:start", "user"), trace.events)
            remoteGate.complete(Unit) // then release the hung remote branch
        }
        runAuthenticatedStartupTasks(
            initDoh = { trace.add("doh") },
            refreshRemoteConfig = {
                trace.add("remote:start")
                remoteGate.await()
                trace.add("remote:end")
            },
            verifyUser = {
                trace.add("user")
                userDone.complete(Unit)
            },
        )

        assertEquals(
            listOf("doh", "remote:start", "user", "remote:end"),
            trace.events,
        )
        watcher.join()
    }
    @Test
    fun `remote config failure does not prevent user verification`() = runTest {
        val trace = Trace()
        val failure = IllegalStateException("boom")
        val failures = mutableListOf<Throwable>()
        val userStarted = CompletableDeferred<Unit>()
        val userCanFinish = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, error -> failures.add(error) })
        try {
            val startup = scope.launch {
                runAuthenticatedStartupTasks(
                    initDoh = { trace.add("doh") },
                    refreshRemoteConfig = {
                        userStarted.await()
                        trace.add("remote:failed")
                        throw failure
                    },
                    verifyUser = {
                        userStarted.complete(Unit)
                        userCanFinish.await()
                        trace.add("user:finished")
                    },
                )
            }
            runCurrent()
            assertEquals(listOf(failure), failures)
            assertTrue("User verification must survive the remote failure", startup.isActive)
            userCanFinish.complete(Unit)
            startup.join()
            assertEquals(listOf("doh", "remote:failed", "user:finished"), trace.events)
        } finally {
            scope.cancel()
        }
    }
}
