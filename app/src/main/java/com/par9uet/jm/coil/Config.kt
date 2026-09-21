package com.par9uet.jm.coil

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import com.par9uet.jm.cache.CacheBudget
import com.par9uet.jm.cache.getCommonCacheDir
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.network.applyAppHttpDefaults
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient

private val cdnHeaderInterceptor = Interceptor { chain ->
    val request = chain.request().newBuilder()
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36")
        .header("Referer", "https://18comic.vip")
        .build()
    chain.proceed(request)
}

/**
 * 封面专用 ImageLoader：独立 Dispatcher，连接池与 CDN 客户端共享。
 * 并发上限：全局 12、每 host 4，避免网格 fling 时打满带宽与 DoH。
 */
fun createAsyncImageLoader(
    context: Context,
    dohManager: DohManager,
    coverDiskCacheMb: Int = CacheBudget.coverDiskCacheMb(CacheBudget.DEFAULT_TOTAL_MB),
    connectionPool: ConnectionPool,
    httpCache: Cache? = null,
): ImageLoader {
    val coverDispatcher = Dispatcher().apply {
        maxRequests = 12
        maxRequestsPerHost = 4
    }
    return ImageLoader.Builder(context)
        .okHttpClient {
            OkHttpClient.Builder()
                .applyAppHttpDefaults(
                    dns = dohManager,
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
