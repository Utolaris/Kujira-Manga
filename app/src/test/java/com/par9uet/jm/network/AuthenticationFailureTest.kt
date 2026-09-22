package com.par9uet.jm.network

import com.par9uet.jm.core.SessionRecoveryException
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.AuthenticatedSessionRequiredException
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.favorites.data.toFavoriteSyncError
import io.github.jukomu.jmcomic.api.exception.ResponseException
import org.junit.Assert.*
import org.junit.Test

class AuthenticationFailureTest {
    @Test
    fun overloadedBackendErrorTextCannotTriggerLogin() {
        for (status in listOf(200, 400, 403, 429, 500, 502, 503, 504)) {
            assertFalse(ResponseException("请先登录，login required，登录失败", status).isAuthenticationFailure())
        }
        assertTrue(ResponseException("rejected", 401).isAuthenticationFailure())
        val body = "Request failed with code: 200, error message: {\"code\":401,\"errorMsg\":\"rejected\"}"
        assertTrue(ResponseException(body, 200).isAuthenticationFailure())
        assertFalse(ResponseException(body, 503).isAuthenticationFailure())
    }

    @Test
    fun sdkFormConstructionFailureDoesNotBecomeAuthenticationFailure() {
        val error = NullPointerException(
            "Parameter specified as non-null is null: method okhttp3.FormBody\$Builder.add, parameter value",
        ).toFavoriteSyncError()
        assertEquals(NetworkErrorKind.Unknown, error.kind)
        assertFalse(error.message.contains("重新登录"))
    }

    @Test
    fun cooldownErrorRetainsItsClassificationEvenWithAnOriginal401Cause() {
        val paused = NetWorkResult.Error(
            "暂时无法确认", kind = NetworkErrorKind.Network,
            authFailure = AuthFailure.TemporaryFailure,
            cause = AuthenticatedSessionRequiredException("rejected", ResponseException("expired", 401)),
        )
        assertSame(paused, SessionRecoveryException(paused).toFavoriteSyncError())
    }
}
