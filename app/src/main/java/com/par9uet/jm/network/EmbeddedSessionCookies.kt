package com.par9uet.jm.network

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Headers

/** Keep response-header AVS out of the candidate jar; SDK login writes the JSON `s` itself. */
internal fun Headers.withoutEmbeddedSessionCookie(): Headers = newBuilder().apply {
    removeAll("Set-Cookie")
    values("Set-Cookie").filterNot {
        it.substringBefore('=').trim() == EMBEDDED_SESSION_COOKIE_NAME
    }.forEach { add("Set-Cookie", it) }
}.build()

/**
 * 内置 API 的**会话令牌** cookie 名。
 *
 * 单写者不变量：只有登录流程（`EmbeddedClientManager.activateCandidateSession`）能写它。
 * 请求侧见 [embeddedCookiesForRequest]，响应侧见 [mergeEmbeddedResponseCookies]。
 */
internal const val EMBEDDED_SESSION_COOKIE_NAME = "AVS"

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
        if (cookie.name != EMBEDDED_SESSION_COOKIE_NAME) return@filter false
        // 不要求原始 domain 已在 trustedDomains：登录域 AVS 同样允许注入到可信 API 域。
        hostOnlyAvs(cookie).matches(url)
    }
    val avs = eligible.firstOrNull { it.name == EMBEDDED_SESSION_COOKIE_NAME && it.matches(url) }
        ?: eligible.firstOrNull { it.name == EMBEDDED_SESSION_COOKIE_NAME }?.let(::hostOnlyAvs)
    return eligible.filterNot { it.name == EMBEDDED_SESSION_COOKIE_NAME } + listOfNotNull(avs)
}

/** Merge Set-Cookie changes instead of trusting SDK getCookies(), which is scoped to loginHost. */
internal fun mergeEmbeddedCookies(
    stored: List<Cookie>,
    received: List<Cookie>,
    now: Long = System.currentTimeMillis(),
): List<Cookie> = (stored + received)
    .associateBy { Triple(it.name, it.domain, it.path) }.values
    .filter { it.expiresAt > now }

/**
 * **网络拦截器专用的响应侧合并：非会话 cookie 照常合并，会话令牌一律忽略。**
 *
 * 为什么必须单独一条规则：原来的合并只判「请求是否打在可信域」，**不判该请求是否需要会话**。
 * 于是任何一次公开 API 响应只要带 `Set-Cookie: AVS=...`，就能
 *  - 按 `(name, domain, path)` 原地**改写**持久化会话快照，或
 *  - 新增一个**同名不同域**的 AVS。
 * 而请求侧 [embeddedCookiesForRequest] 在多个同名 AVS 之间是**按列表顺序取第一个**，
 * 一旦选到旧值，表现就是「登录成功、几秒后所有认证请求 401」。
 *
 * 对齐官方语义：JMComic3 v2.1.8 从不合并 CookieJar，它发出的
 * `Cookie: AVS=<最近一次登录响应的 s>`（`api/HttpUtil.ts:111,155,208,262`）——
 * **会话令牌只有一个来源：登录响应**。这里把这条不变量落到响应侧。
 *
 * 代价：若服务端将来真的通过普通响应轮换 AVS，我们不会跟进。
 * 那等于改用官方不存在的机制，届时应当在登录流程里显式处理，而不是靠副作用合并。
 */
internal fun mergeEmbeddedResponseCookies(
    stored: List<Cookie>,
    received: List<Cookie>,
    now: Long = System.currentTimeMillis(),
): List<Cookie> = mergeEmbeddedCookies(
    stored = stored,
    received = received.filterNot { it.name == EMBEDDED_SESSION_COOKIE_NAME },
    now = now,
)
