package com.par9uet.jm.network

import okhttp3.Request

/**
 * 上游 `/search` 等接口的 `lang` 参数。
 *
 * 官方 app v2.1.8 的 `HttpUtil.fetchGet` 会给**每一个** GET 无条件补 `lang`
 * （`if (!searchParams.has("lang")) searchParams.set("lang", localStorage.lang || "TW")`），
 * 可取值只 `"TW"` / `"CN"` 两个；而依赖的 SDK 1.1.8 只在 `getNovelChapter` 等 3 个方法里发。
 *
 * SDK 是黑盒 jar 改不了，所以在应用层补齐。三条边界都照抄官方行为：
 * 1. **只补 GET** —— 官方的 `fetchPost` / `fetchPostJson` 不补（正文走 FormData / JSON）。
 * 2. **只补 API 域** —— 图床 CDN 不带上，与会话 cookie 的发送面保持一致。
 * 3. **已有 `lang` 不覆盖** —— SDK 自己会发的那几处保持原样。
 *
 * 若以后加语言开关，只需要改这一个常量（官方语义：`TW` = 繁體，`CN` = 简体）。
 */
internal const val EMBEDDED_LANG = "TW"

internal fun Request.withEmbeddedLang(apiDomains: Collection<String>): Request {
    if (method != "GET") return this
    if (url.host !in apiDomains) return this
    if (url.queryParameter("lang") != null) return this
    return newBuilder()
        .url(url.newBuilder().addQueryParameter("lang", EMBEDDED_LANG).build())
        .build()
}
