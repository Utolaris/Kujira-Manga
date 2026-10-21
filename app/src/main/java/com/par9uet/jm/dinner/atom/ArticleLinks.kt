package com.par9uet.jm.dinner.atom

import com.par9uet.jm.dinner.model.ArticleText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 原文 URL / 裸 JM 编码统一为渲染层可直接路由的内部链接；其他 HTTPS 链接保留。 */
internal fun normalizeArticleLink(raw: String): String? {
    val value = raw.trim().trimEnd('.', ',', '，', '。', '；', ';', ')', '）', '】')
    Regex("(?i)^JM\\s*[:：#-]?\\s*([1-9]\\d*)$").matchEntire(value)?.let {
        return positiveId(it.groupValues[1])?.let { id -> "jm://comic/$id" }
    }
    Regex("^jm://(comic|article)/([1-9]\\d*)$").matchEntire(value)?.let {
        return positiveId(it.groupValues[2])?.let { id -> "jm://${it.groupValues[1]}/$id" }
    }
    val url = value.toHttpUrlOrNull() ?: safeArticleUrl(value, "https://18comic.vip/")?.toHttpUrlOrNull() ?: return null
    val segments = url.pathSegments.filter { it.isNotBlank() }
    for ((index, segment) in segments.withIndex()) {
        val id = segments.getOrNull(index + 1)?.let(::positiveId) ?: continue
        when (segment.lowercase()) {
            "album", "photo" -> return "jm://comic/$id"
            "blog", "blogs" -> if (index + 2 == segments.size) return "jm://article/$id"
        }
    }
    val queryId = url.queryParameter("id")?.let(::positiveId)
    if (queryId != null) {
        if (url.encodedPath.trimEnd('/') in setOf("/comic/detail", "/album")) return "jm://comic/$queryId"
        if (url.encodedPath.trimEnd('/') in setOf("/blog", "/blogs/detail")) return "jm://article/$queryId"
    }
    return url.toString()
}

private fun positiveId(raw: String): Int? = raw.toIntOrNull()?.takeIf { it > 0 }

/** 匹配整个段落，因而 JM 与数字被作者拆在不同 span 内也能变为同一链接。 */
internal fun linkifyArticleText(runs: List<ArticleText>): List<ArticleText> {
    val text = runs.joinToString("") { it.text }
    val candidates = mutableListOf<Pair<IntRange, String>>()
    val patterns = listOf(
        Regex("https?://[^\\s<>\"'，。！？；（）【】]+", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])JM\\s*[:：#-]?\\s*[1-9]\\d*", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])/?blogs?/[1-9]\\d*/?(?:\\?[^\\s<>\"'，。！？；（）【】]*)?", RegexOption.IGNORE_CASE),
    )
    patterns.forEach { pattern -> pattern.findAll(text).forEach { match ->
        if (candidates.none { (range, _) -> range.first <= match.range.last && match.range.first <= range.last }) {
            normalizeArticleLink(match.value)?.let { candidates += match.range to it }
        }
    } }
    // 有些传送门只写数字，须带明确标签才识别，避免把日期或普通数字当漫画。
    Regex("(?:传送门|傳送門)\\s*[:：]?\\s*([1-9]\\d*)").findAll(text).forEach { match ->
        val group = match.groups[1] ?: return@forEach
        positiveId(group.value)?.let { id ->
            if (candidates.none { (range, _) -> range.first <= group.range.last && group.range.first <= range.last }) {
                candidates += group.range to "jm://comic/$id"
            }
        }
    }
    var offset = 0
    return runs.flatMap { run ->
        val start = offset
        val end = start + run.text.length
        offset = end
        val overlapping = candidates.filter { (range, _) -> range.first < end && range.last >= start }
        val cuts = (listOf(start, end) + overlapping.flatMap { (range, _) ->
            listOf(maxOf(start, range.first), minOf(end, range.last + 1))
        }).distinct().sorted()
        cuts.zipWithNext().map { (a, b) ->
            val detected = overlapping.firstOrNull { (range, _) -> a in range }?.second
            run.copy(text = text.substring(a, b), link = detected ?: run.link?.let(::normalizeArticleLink))
        }
    }
}
