package com.par9uet.jm.store
import com.par9uet.jm.session.AuthenticatedSessionGate
import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.session.SessionReadiness
import com.par9uet.jm.session.SessionReadinessHolder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

/** Component tests of the production authentication gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGateBehaviorTest {
    @Test
    fun `restoration gates authenticated work until the session is ready`() = runTest {
        val readiness = SessionReadinessHolder().apply { set(SessionReadiness.Restoring) }
        val gate = AuthenticatedSessionGate(readiness)
        var calls = 0
        val request = launch { gate.run { calls++ } }

        runCurrent()
        assertFalse(request.isCompleted)
        assertEquals(0, calls)
        readiness.set(SessionReadiness.Authenticated)
        request.join()
        assertEquals(1, calls)
    }

    @Test
    fun `logout readiness rejects stale work and cancellation propagates`() = runTest {
        val readiness = SessionReadinessHolder().apply { set(SessionReadiness.Unauthenticated) }
        val gate = AuthenticatedSessionGate(readiness)

        assertFalse(readiness.state.value == SessionReadiness.Authenticated)
        var calls = 0
        val error = assertThrows(AuthenticatedSessionRequiredException::class.java) {
            kotlinx.coroutines.runBlocking { gate.run { calls++ } }
        }
        assertTrue(error.message!!.contains("登录"))
        assertEquals(0, calls)

        readiness.set(SessionReadiness.Authenticated)
        val cancellation = CancellationException("switch account")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { gate.run<Unit> { throw cancellation } }
        })
    }
}
