package com.par9uet.jm.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormBodyNullParameterTest {

    @Test
    fun `okhttp form body non-null message is recognized`() {
        val error = NullPointerException(
            "Parameter specified as non-null is null: method okhttp3.FormBody\$Builder.add, " +
                "parameter value",
        )
        assertTrue(error.isFormBodyNullParameter())
    }

    @Test
    fun `nested cause form body failure is recognized`() {
        val error = RuntimeException(
            "sync failed",
            NullPointerException(
                "Parameter specified as non null is null: method okhttp3.FormBody\$Builder.add",
            ),
        )
        assertTrue(error.isFormBodyNullParameter())
    }

    @Test
    fun `unrelated kotlin non-null assertion is not swallowed`() {
        val error = NullPointerException(
            "Parameter specified as non-null is null: parameter callback",
        )
        assertFalse(error.isFormBodyNullParameter())
    }
}
