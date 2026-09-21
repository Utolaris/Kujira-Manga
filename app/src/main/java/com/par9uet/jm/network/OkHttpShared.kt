package com.par9uet.jm.network

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.par9uet.jm.BuildConfig

/** CDN/元数据共享连接池：各客户端可独立 Dispatcher，但共用 TLS/HTTP2 连接。 */
fun createCdnConnectionPool(): ConnectionPool =
    ConnectionPool(maxIdleConnections = 10, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES)

/** HTTP 响应缓存（ETag/304）。阅读页自研磁盘缓存，不挂此 Cache。 */
fun createHttpCache(directory: File, maxBytes: Long): Cache {
    directory.mkdirs()
    return Cache(directory, maxBytes)
}

/**
 * 统一的 OkHttp 基建：共享连接池 + 超时 + 可选 HTTP Cache + DEBUG 门控日志。
 * Dispatcher 仍由各调用方按业务隔离（封面 fling / 阅读 / 下载）。
 * 归属 L4 `network`；`core/network` 只保留无行为契约。
 */
fun OkHttpClient.Builder.applyAppHttpDefaults(
    dns: Dns,
    connectionPool: ConnectionPool,
    cache: Cache? = null,
    connectSeconds: Long = 15,
    readSeconds: Long = 30,
    writeSeconds: Long = 30,
    callSeconds: Long = 40,
): OkHttpClient.Builder {
    dns(dns)
    connectionPool(connectionPool)
    if (cache != null) cache(cache)
    connectTimeout(connectSeconds, TimeUnit.SECONDS)
    readTimeout(readSeconds, TimeUnit.SECONDS)
    writeTimeout(writeSeconds, TimeUnit.SECONDS)
    callTimeout(callSeconds, TimeUnit.SECONDS)
    followRedirects(true)
    return this
}

/** 无 cookie 的共享客户端（探针 / 元数据 / GitHub）。 */
fun createSharedCookielessDohClient(
    dns: Dns,
    connectionPool: ConnectionPool,
    cache: Cache? = null,
): OkHttpClient = OkHttpClient.Builder()
    .applyAppHttpDefaults(
        dns = dns,
        connectionPool = connectionPool,
        cache = cache,
        connectSeconds = 10,
        readSeconds = 15,
        writeSeconds = 15,
        callSeconds = 20,
    )
    .cookieJar(CookieJar.NO_COOKIES)
    .applyHttpLogging()
    .build()

fun OkHttpClient.Builder.applyHttpLogging(): OkHttpClient.Builder {
    if (BuildConfig.DEBUG) {
        addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
    }
    return this
}
