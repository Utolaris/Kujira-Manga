package com.par9uet.jm.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupPhasesTest {
    @Test fun `maintenance waits for auth local restoration and visible content then unread runs last`() = runTest {
        val auth = CompletableDeferred<Unit>()
        val local = CompletableDeferred<Unit>()
        val content = CompletableDeferred<Unit>()
        val maintenance = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            runStartupPhases(
                authenticated = { events += "auth"; auth.await() },
                local = { events += "local"; local.await() },
                awaitContent = { events += "content"; content.await() },
                maintenance = { events += "maintenance"; maintenance.await() },
                unread = { events += "unread" },
            )
        }
        runCurrent()
        assertEquals(listOf("auth", "local"), events)
        auth.complete(Unit); runCurrent()
        assertEquals(listOf("auth", "local", "content"), events)
        content.complete(Unit); runCurrent()
        assertFalse("Local restoration must finish first", "maintenance" in events)
        local.complete(Unit); runCurrent()
        assertEquals("maintenance", events.last())
        maintenance.complete(Unit); job.join()
        assertEquals("unread", events.last())
    }

    @Test fun `cancelling startup does not leave detached maintenance work`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        var ran = false
        val job = launch {
            runStartupPhases({ waiting.await() }, {}, {}, { ran = true }, { ran = true })
        }
        runCurrent()
        job.cancel(); job.join()
        waiting.complete(Unit); runCurrent()
        assertFalse(ran)
    }
}
