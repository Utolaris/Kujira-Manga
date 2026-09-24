package com.par9uet.jm.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactSensitiveJsonTest {
    @Test fun `login JWT and AVS are absent from diagnostic text`() {
        val redacted = redactSensitiveJson("{\"jwttoken\":\"secret-jwt\",\"s\":\"secret-avs\",\"uid\":7}")
        assertFalse(redacted.contains("secret-jwt"))
        assertFalse(redacted.contains("secret-avs"))
        assertTrue(redacted.contains("\"uid\":7"))
    }
}
