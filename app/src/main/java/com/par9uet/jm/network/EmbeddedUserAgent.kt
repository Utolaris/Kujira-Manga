package com.par9uet.jm.network

import android.content.Context
import android.webkit.WebSettings
import okhttp3.Request

/**
 * 内置 API 请求用的 `User-Agent` 来源。
 *
 * SDK 1.1.8 把它硬编码为 `JmConstants.DEFAULT_USER_AGENT_API`：
 *
 * ```
 * Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36
 *   (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36
 * ```
 *
 * 后果是**所有设备、所有版本发出去的 UA 完全一样**，而且永远停在 Android 9 / Chrome 91（2021 年）——
 * 这是一个极其稳定、极其好用的"第三方客户端"指纹。
 * 官方 app 是 Capacitor WebView，发的是**设备自己的 WebView UA**（Android 版本、机型、当前 WebView 版本）。
 *
 * 做成 `fun interface` 是为了让 DI 直接给 lambda，从而**不需要在 Koin 图里注入 `Context`**
 * （否则纯 JVM 的 Koin 装配测试会因缺 androidContext 而失败）。
 */
fun interface EmbeddedUserAgentProvider {
    /** null = 尚未就绪（预热失败），此时保留 SDK 默认值。 */
    fun userAgent(): String?
}

/**
 * 进程级设备 WebView UA 缓存。
 *
 * `WebSettings.getDefaultUserAgent()` 会初始化 WebView，**必须在主线程调用**
 * （在无 Looper 的线程上会抛 "Can't create handler inside thread that has not called Looper.prepare()"）。
 * 所以由 `Application.onCreate` 预热一次，之后请求线程只读缓存。
 */
object DeviceWebViewUserAgent {
    @Volatile
    private var value: String? = null

    /** 只在主线程调用。失败静默：拿不到就继续用 SDK 默认 UA，不影响功能。 */
    fun warmUp(context: Context) {
        if (value != null) return
        value = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun current(): String? = value
}

/**
 * 覆盖 `User-Agent`。SDK 的 `UserAgentInterceptor` 是**先于**本拦截器加入的应用层拦截器
 * （它在最外层，已经写过一次 UA），所以这里直接改写是安全的 —— 后写的生效。
 *
 * 图床请求走同一个 OkHttpClient，同样会被改成设备 UA；官方在浏览器里也是这个行为，保持一致。
 */
internal fun Request.withEmbeddedUserAgent(userAgent: String?): Request {
    if (userAgent.isNullOrBlank()) return this
    if (header("User-Agent") == userAgent) return this
    return newBuilder().header("User-Agent", userAgent).build()
}
