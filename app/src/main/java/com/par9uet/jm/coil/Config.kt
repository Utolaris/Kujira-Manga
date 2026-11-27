package com.par9uet.jm.coil

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.getCommonCacheDir
import com.par9uet.jm.network.DeviceWebViewUserAgent
import com.par9uet.jm.network.applyAppHttpDefaults
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient

private val cdnHeaderInterceptor = Interceptor { chain ->
    val request = chain.request().newBuilder()
        .header("User-Agent", DeviceWebViewUserAgent.currentOrChromeFallback())
        .header("Referer", "https://18comic.vip")
        .build()
    chain.proceed(request)
}

/**
 * 封面专用 ImageLoader：独立 Dispatcher，连接池与 CDN 客户端共享。
 * 传输层上限：全局 12、每 host 4；带门控参数的封面另由 Fetcher 限制为两路。
 */
fun createAsyncImageLoader(
    context: Context,
    dns: Dns,
    coverDiskCacheMb: Int = CacheBudget.coverDiskCacheMb(CacheBudget.DEFAULT_TOTAL_MB),
    connectionPool: ConnectionPool,
    httpCache: Cache? = null,
): ImageLoader {
    val coverDispatcher = Dispatcher().apply {
        maxRequests = 12
        maxRequestsPerHost = 4
    }
    val thumbnailCache = CoverThumbnailCache(File(getCommonCacheDir(context), "thumbs"))
    return ImageLoader.Builder(context)
        .components {
            add(VisibleCoverFetcherFactory(thumbnailCache))
            add(VisibleCoverDecoderFactory(thumbnailCache))
            add(coil.decode.ImageDecoderDecoder.Factory())
        }
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.30)
                .build()
        }
        .okHttpClient {
            OkHttpClient.Builder()
                .applyAppHttpDefaults(
                    dns = dns,
                    connectionPool = connectionPool,
                    cache = httpCache,
                    connectSeconds = 15,
                    readSeconds = 30,
                    writeSeconds = 30,
                    callSeconds = 40,
                )
                .addInterceptor(cdnHeaderInterceptor)
                .dispatcher(coverDispatcher)
                .build()
        }
        // coverDiskCacheMb 已是组件配额（MB，由总预算 × COVER_SHARE 推导），
        // 这里只换算字节，不得再当作总额度二次分享额。
        .diskCache {
            DiskCache.Builder()
                .directory(getCommonCacheDir(context))
                .maxSizeBytes(CacheBudget.componentMbToBytes(coverDiskCacheMb))
                .build()
        }
        .build()
}
