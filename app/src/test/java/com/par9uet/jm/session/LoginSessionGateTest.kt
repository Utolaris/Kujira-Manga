package com.par9uet.jm.session

import com.par9uet.jm.retrofit.model.LoginResponse
import okhttp3.Cookie
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LoginSessionGateTest {
    @Test fun `valid AVS accepts candidate`() {
        assertNull(LoginSessionGate.validateCandidate(candidate(listOf(cookie("AVS", "session")))))
    }

    @Test fun `unrelated cookie cannot stand in for AVS`() {
        assertNotNull(LoginSessionGate.validateCandidate(candidate(listOf(cookie("__cflb", "affinity")))))
    }

    @Test fun `empty or expired AVS cannot authenticate`() {
        assertNotNull(LoginSessionGate.validateCandidate(candidate(listOf(cookie("AVS", "")))))
        assertNotNull(LoginSessionGate.validateCandidate(candidate(listOf(
            Cookie.Builder().name("AVS").value("old").domain("18comic.vip").expiresAt(1L).build(),
        ))))
    }

    private fun candidate(cookies: List<Cookie>) = CandidateSession(
        LoginResponse(7, "A", "", "", "0", 0, "M", 1, 100, 0, 0.0, 100),
        cookies,
    )

    private fun cookie(name: String, value: String) = Cookie.Builder()
        .name(name).value(value).domain("18comic.vip").path("/").build()
}
