package com.par9uet.jm.network

import okhttp3.Request

/**
 * 搜索结果的**年/月**筛选（官方 `/search` 的 `y` / `m`）。
 *
 * 官方 app v2.1.8 在出结果后用两个下拉筛选年月：选了才发对应参数，空值省略
 * （`HttpUtil.fetchGet` 会滤掉 `""`）。SDK 1.1.8 的 `SearchQuery` 没有这两个字段，
 * `search()` 只会发相对时间 `t=t|w|m|a`。`t` 与 `y`/`m` 语义冲突，写去年月时一并去掉。
 *
 * 与 [withEmbeddedLang] 同一补参路径：SDK 黑盒 jar 改不了，在应用层拦截器补齐。
 * 取值通过 [EmbeddedSearchDateScope] 在 `client.search()` 调用期间注入，
 * 因为 SDK 组装 URL 时无法带应用层上下文。
 */
data class EmbeddedSearchDate(
    val year: String? = null,
    val month: String? = null,
) {
    val isEmpty: Boolean
        get() = year.isNullOrBlank() && month.isNullOrBlank()

    companion object {
        val EMPTY = EmbeddedSearchDate()
    }
}

/**
 * 调用栈内的一次搜索年月上下文。
 *
 * `client.search()` 是同步 OkHttp 调用，应用拦截器跑在同一线程上，
 * 因此 ThreadLocal 在 `withDate { ... }` 作用域内是安全的；作用域退出即恢复上一值，
 * 嵌套调用（如按标签搜 ID）不会串味。
 */
object EmbeddedSearchDateScope {
    private val holder = ThreadLocal<EmbeddedSearchDate?>()

    fun current(): EmbeddedSearchDate = holder.get() ?: EmbeddedSearchDate.EMPTY

    fun <T> withDate(date: EmbeddedSearchDate, block: () -> T): T {
        val previous = holder.get()
        holder.set(date)
        try {
            return block()
        } finally {
            holder.set(previous)
        }
    }
}

/**
 * 给 `/search` 补官方的 `y`/`m`，并在写入任一维度时去掉 SDK 的 `t`。
 *
 * 边界与 [withEmbeddedLang] 对齐：
 * 1. 只改 GET；
 * 2. 只改 API 域；
 * 3. 只改路径末段为 `search` 的请求（分类/收藏等同域接口不动）；
 * 4. 两个维度都为空时原样放行 —— 未设年月就是「不限」，保持 SDK 原行为。
 */
internal fun Request.withEmbeddedSearchDate(
    apiDomains: Collection<String>,
    date: EmbeddedSearchDate = EmbeddedSearchDateScope.current(),
): Request {
    if (method != "GET") return this
    if (url.host !in apiDomains) return this
    if (url.pathSegments.lastOrNull() != "search") return this
    if (date.isEmpty) return this

    val builder = url.newBuilder()
    // SDK 恒发 t=a（全部）；与官方 y/m 同用会互相干扰，写入年月时去掉。
    builder.removeAllQueryParameters("t")
    val year = date.year?.takeIf { it.isNotBlank() }
    val month = date.month?.takeIf { it.isNotBlank() }
    if (year != null) {
        builder.removeAllQueryParameters("y")
        builder.addQueryParameter("y", year)
    }
    if (month != null) {
        builder.removeAllQueryParameters("m")
        builder.addQueryParameter("m", month)
    }
    return newBuilder().url(builder.build()).build()
}
