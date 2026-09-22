package com.par9uet.jm.network

import okhttp3.Request

/** The host comes only from the active client's observed trusted HTTPS requests. */
internal fun Request.withEmbeddedLoginHost(host: String?, apiDomains: Collection<String>): Request {
    if (host == null || !url.isHttps || url.host !in apiDomains || url.encodedPath != "/login") {
        return this
    }
    // Runs after the SDK's retry/domain interceptor, so its own race cannot move this login.
    return newBuilder().url(url.newBuilder().host(host).build()).build()
}
