package com.par9uet.jm.coil

import com.par9uet.jm.image.JM_IMAGE_HOSTS
import com.par9uet.jm.image.JM_IMAGE_HOST_COOLDOWN_MILLIS
import com.par9uet.jm.image.JmImageHostHealth
import com.par9uet.jm.image.JmImageHostHealthStore
import com.par9uet.jm.image.normalizeJmImageHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl

/** Sequential Coil cover fallback backed by the same ranked health state as Reader. */
class CoverImageHostResolver internal constructor(
    private val hostHealth: JmImageHostHealth,
    private val maxCandidates: Int = MAX_COVER_CANDIDATES,
) {
    internal constructor(
        knownHosts: List<String> = JM_IMAGE_HOSTS,
        clockMillis: () -> Long = System::currentTimeMillis,
        failureCooldownMillis: Long = JM_IMAGE_HOST_COOLDOWN_MILLIS,
        maxCandidates: Int = MAX_COVER_CANDIDATES,
    ) : this(
        hostHealth = JmImageHostHealthStore(
            knownHosts = knownHosts,
            clockMillis = clockMillis,
            cooldownMillis = failureCooldownMillis,
            maxHosts = maxCandidates,
        ),
        maxCandidates = maxCandidates,
    )

    val preferredHost = hostHealth.preferredHost
    val networkGeneration = hostHealth.networkGeneration

    fun coverUrls(comicId: Int, remoteHost: String?): List<String> = candidateHosts(remoteHost)
        .map { host -> buildJmCoverUrl(host, comicId) }

    /** 文章图片保留完整路径和查询，只对 JM 图床启用同一份测速排序及串行镜像回退。 */
    fun articleUrls(rawUrl: String, remoteHost: String?): List<String> {
        val url = rawUrl.toHttpUrlOrNull() ?: return emptyList()
        if (!isJmArticleImage(url, remoteHost)) return listOf(rawUrl)
        val hosts = candidateHosts(remoteHost)
        hostHealth.registerHost(url.host)
        return (hosts + url.host).distinct().map { host ->
            url.newBuilder().scheme("https").port(443).host(host).build().toString()
        }
    }

    fun articleCacheKey(rawUrl: String, remoteHost: String?): String {
        val url = rawUrl.toHttpUrlOrNull()
        if (url != null && isJmArticleImage(url, remoteHost)) {
            val id = Regex("^/media/albums/([1-9]\\d*)_3x4\\.jpg$")
                .matchEntire(url.encodedPath)?.groupValues?.get(1)?.toIntOrNull()
            if (id != null) return jmCoverCacheKey(id)
        }
        return "jm-article-$rawUrl"
    }

    private fun isJmArticleImage(url: HttpUrl, remoteHost: String?): Boolean {
        val jmHost = url.host == normalizeJmImageHost(remoteHost) || url.host in JM_IMAGE_HOSTS ||
            Regex("cdn-msp\\d*\\.jmapiproxy\\d+\\.cc").matches(url.host) ||
            Regex("cdn-msp\\d*\\.jmapinode[a-z0-9]+\\.net").matches(url.host)
        val imagePath = url.encodedPath.startsWith("/media/albums/") ||
            url.encodedPath.startsWith("/media/photos/") ||
            url.encodedPath.startsWith("/static/resources/images/") ||
            url.encodedPath.startsWith("/media/blogs/")
        return jmHost && imagePath
    }

    /**
     * 封面加载成功：只标记主机健康（清除冷却），不把 Coil 全量加载耗时
     * （含排队/下载/解码/渲染）写入 Reader 排序使用的 TTFB 延迟 EWMA。
     */
    fun recordHealthy(candidateUrl: String) {
        hostHealth.recordHealthy(candidateUrl)
    }

    /** 主机/网络级失败才调用；资源级失败（404/解码等）不应全局惩罚 CDN。 */
    fun recordHostFailure(candidateUrl: String) {
        hostHealth.recordHostFailure(candidateUrl)
    }

    internal fun candidateHosts(remoteHost: String?): List<String> {
        hostHealth.registerHost(remoteHost)
        return hostHealth.orderedHosts(normalizeJmImageHost(remoteHost))
            .take(maxCandidates.coerceAtLeast(1))
    }

    private companion object {
        private const val MAX_COVER_CANDIDATES = 8
    }
}

internal fun buildJmCoverUrl(host: String, comicId: Int): String =
    "https://$host/media/albums/${comicId}_3x4.jpg"

internal fun jmCoverCacheKey(comicId: Int): String = "jm-cover-$comicId"

/** Returns one next candidate; the caller starts no parallel mirror request. */
internal fun nextCoverCandidateUrl(
    candidates: List<String>,
    attemptedUrls: Set<String>,
): String? = candidates.firstOrNull { it !in attemptedUrls }
