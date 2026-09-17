package com.par9uet.jm.coil

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import com.par9uet.jm.cache.getCommonCacheDir
import com.par9uet.jm.network.DohManager
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 封面磁盘缓存默认上限（MB）。可在设置中调整；Coil 在 ImageLoader 构建时固定上限。 */
const val DEFAULT_COVER_DISK_CACHE_MB = 256

val COVER_DISK_CACHE_MB_OPTIONS = listOf(128, 256, 512, 1024)

fun coerceCoverDiskCacheMb(mb: Int): Int = when {
    mb <= 128 -> 128
    mb <= 256 -> 256
    mb <= 512 -> 512
    else -> 1024
}

private val cdnHeaderInterceptor = Interceptor { chain ->
    val request = chain.request().newBuilder()
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36")
        .header("Referer", "https://18comic.vip")
        .build()
    chain.proceed(request)
}

/**
 * 封面专用 ImageLoader：独立 OkHttp 客户端与 Dispatcher，和下载/阅读的连接池隔离。
 * 并发上限：全局 12、每 host 4，避免网格 fling 时打满带宽与 DoH。
 */
fun createAsyncImageLoader(
    context: Context,
    dohManager: DohManager,
    coverDiskCacheMb: Int = DEFAULT_COVER_DISK_CACHE_MB,
): ImageLoader {
    val coverDispatcher = Dispatcher().apply {
        maxRequests = 12
        maxRequestsPerHost = 4
    }
    return ImageLoader.Builder(context)
        .okHttpClient {
            OkHttpClient.Builder()
                .dns(dohManager)
                .addInterceptor(cdnHeaderInterceptor)
                .dispatcher(coverDispatcher)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(getCommonCacheDir(context))
                .maxSizeBytes(coerceCoverDiskCacheMb(coverDiskCacheMb).toLong() * 1024L * 1024L)
                .build()
        }
        .build()
}
