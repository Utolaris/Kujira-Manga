package com.par9uet.jm.image

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class ImageHostFailureClassifierTest(
    private val name: String,
    private val error: Throwable?,
    private val httpCode: Int?,
    private val expected: ImageHostFailureKind,
) {
    @Test fun classifiesFailure() {
        assertEquals(name, expected, classifyImageHostFailure(error, httpCodeHint = httpCode))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("HTTP 404", RuntimeException("HTTP 404"), 404, ImageHostFailureKind.RESOURCE_FAILURE),
            arrayOf("HTTP 410", RuntimeException("HTTP 410"), 410, ImageHostFailureKind.RESOURCE_FAILURE),
            arrayOf("HTTP 403", null, 403, ImageHostFailureKind.RESOURCE_FAILURE),
            arrayOf("bitmap decode", IllegalStateException("BitmapFactory returned null"), null, ImageHostFailureKind.RESOURCE_FAILURE),
            arrayOf("decode", RuntimeException("Failed to decode image"), null, ImageHostFailureKind.RESOURCE_FAILURE),
            arrayOf("truncated resource", EOFException("stream closed"), null, ImageHostFailureKind.RESOURCE_FAILURE),
            arrayOf("timeout", SocketTimeoutException("timeout"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("connect", ConnectException("connection refused"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("DNS", UnknownHostException("cdn.example"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("route", NoRouteToHostException("no route"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("reset", SocketException("connection reset"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("handshake", SSLHandshakeException("handshake failed"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("TLS", SSLException("tls error"), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("wrapped timeout", IllegalStateException("load failed", SocketTimeoutException("read timed out")), null, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("HTTP 500", null, 500, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("HTTP 502", null, 502, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("HTTP 429", null, 429, ImageHostFailureKind.HOST_FAILURE),
            arrayOf("cancelled", CancellationException("navigated away"), null, ImageHostFailureKind.CANCELLED),
            arrayOf("wrapped cancellation", RuntimeException(CancellationException("job cancelled")), null, ImageHostFailureKind.CANCELLED),
            arrayOf("cancellation before HTTP", CancellationException("cancelled"), 500, ImageHostFailureKind.CANCELLED),
            arrayOf("unknown", IllegalArgumentException("weird"), null, ImageHostFailureKind.UNKNOWN),
            arrayOf("no error", null, null, ImageHostFailureKind.UNKNOWN),
            arrayOf("unknown IO", IOException("unspecified I/O failure"), null, ImageHostFailureKind.UNKNOWN),
        )
    }
}

class ImageHostFailureCauseChainTest {
    @Test
    fun directAndWrappedCancellationArePropagatable() {
        val direct = CancellationException("direct")
        val wrapped = IllegalStateException("wrapped", direct)

        assertTrue(direct.isCancellation())
        assertTrue(wrapped.isCancellation())
        assertSame(direct, direct.cancellationExceptionOrNull())
        assertSame(direct, wrapped.cancellationExceptionOrNull())
    }

    @Test
    fun causeChainCycleDoesNotLoopForever() {
        val a = RuntimeException("a")
        val b = IOException("plain-io", a)
        a.initCause(b)

        assertEquals(ImageHostFailureKind.UNKNOWN, classifyImageHostFailure(a))
    }
}
