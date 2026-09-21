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
 * 取值由用户设置决定（默认简体），见 [EmbeddedRequestLanguageProvider]。
 *
 * 合法取值的定义放在 `data.models`（与 UI 候选列表同源），**这里不做校验**：
 * `network` 层不得依赖 `data.*`（见 `ArchitectureBoundaryTest`），所以合法性由组合根
 * （`di/ComicModule.kt` 绑定 provider 时读 LocalSettingManager，它已经收口过）保证。
 * 拿不到取值就整个不补 `lang` —— 这正是 SDK 原本的行为，比发一个空值安全。
 */
fun interface EmbeddedRequestLanguageProvider {
    /** `"CN"` = 简体，`"TW"` = 繁體。null / 空 = 尚未就绪。 */
    fun language(): String?
}

internal fun Request.withEmbeddedLang(
    apiDomains: Collection<String>,
    language: String?,
): Request {
    if (method != "GET") return this
    if (url.host !in apiDomains) return this
    if (url.queryParameter("lang") != null) return this
    val lang = language?.takeIf { it.isNotBlank() } ?: return this
    return newBuilder()
        .url(url.newBuilder().addQueryParameter("lang", lang).build())
        .build()
}
