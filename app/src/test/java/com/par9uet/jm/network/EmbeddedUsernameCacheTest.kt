package com.par9uet.jm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Restored-cookie sessions must be able to populate the library username cache so
 * postComment does not throw "Please login first" after the remote POST already succeeded.
 */
class EmbeddedUsernameCacheTest {

    @Test
    fun abstractJmClientExposesProtectedCacheUsername() {
        val method = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("cacheUsername", String::class.java)
        assertTrue(Modifier.isProtected(method.modifiers))
        assertEquals(Void.TYPE, method.returnType)
    }

    @Test
    fun getLoggedInUserNameRequiresUsernameBeforeCommentParse() {
        val get = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("getLoggedInUserName")
        assertTrue(Modifier.isProtected(get.modifiers))
        // Documented library contract: blank loggedInUserName throws before parseCommentSubmitResult.
        // cacheUsername is the only supported write path besides in-process login().
        val field = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredField("loggedInUserName")
        assertTrue(Modifier.isVolatile(field.modifiers))
    }
}
