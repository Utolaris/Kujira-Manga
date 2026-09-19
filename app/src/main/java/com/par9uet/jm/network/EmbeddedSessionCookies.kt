package com.par9uet.jm.network

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * AVS 可能挂在登录站点域名上（如 18comic.vip），而收藏等业务请求打到 SDK 轮换的 API 域名。
 * 请求层拦截器只发「按域名过滤后的 Cookie」，若 AVS 必须 `domain in trustedDomains` 才会注入，
 * 登录成功后收藏 API 会 401 Authentication fail。这里对 AVS 在可信 API 域名上做 host-only 重挂。
 */
internal fun embeddedCookiesForRequest(
    stored: List<Cookie>,
    url: HttpUrl,
    trustedDomains: Collection<String>,
    now: Long = System.currentTimeMillis(),
): List<Cookie> {
    if (!url.isHttps || url.host !in trustedDomains) return emptyList()
    fun hostOnlyAvs(source: Cookie): Cookie = Cookie.Builder()
        .name(source.name)
        .value(source.value)
        .hostOnlyDomain(url.host)
        .path(source.path)
        .secure()
        .build()

    val eligible = stored.filter { cookie ->
        if (cookie.expiresAt <= now) return@filter false
        if (cookie.matches(url)) return@filter true
        if (cookie.name != "AVS") return@filter false
        // 不要求原始 domain 已在 trustedDomains：登录域 AVS 同样允许注入到可信 API 域。
        hostOnlyAvs(cookie).matches(url)
    }
    val avs = eligible.firstOrNull { it.name == "AVS" && it.matches(url) }
        ?: eligible.firstOrNull { it.name == "AVS" }?.let(::hostOnlyAvs)
    return eligible.filterNot { it.name == "AVS" } + listOfNotNull(avs)
}

/** Merge Set-Cookie changes instead of trusting SDK getCookies(), which is scoped to loginHost. */
internal fun mergeEmbeddedCookies(
    stored: List<Cookie>,
    received: List<Cookie>,
    now: Long = System.currentTimeMillis(),
): List<Cookie> = (stored + received)
    .associateBy { Triple(it.name, it.domain, it.path) }.values
    .filter { it.expiresAt > now }
