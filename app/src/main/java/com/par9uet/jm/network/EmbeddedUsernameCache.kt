package com.par9uet.jm.network

import com.par9uet.jm.utils.log
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient

/**
 * JMComic-Api-Java only fills `loggedInUserName` during an in-process `login()`.
 * Cookie restore (`setCookies`) leaves it blank, so `postComment`/`replyToComment` throw
 * "Username is required... Please login first." *after* the remote POST succeeded.
 * This calls the protected `cacheUsername` so restored sessions behave like logged-in ones.
 */
internal fun cacheEmbeddedLoggedInUserName(client: JmApiClient, username: String) {
    if (username.isBlank()) return
    runCatching {
        val method = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("cacheUsername", String::class.java)
        method.isAccessible = true
        method.invoke(client, username)
    }.onFailure {
        log("EmbeddedUsernameCache", "写入内置 API username 缓存失败：" + it.message)
    }
}
