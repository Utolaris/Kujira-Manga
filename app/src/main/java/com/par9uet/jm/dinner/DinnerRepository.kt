package com.par9uet.jm.dinner

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.dinner.atom.ArticlePromotionVerdict
import com.par9uet.jm.dinner.atom.articlePromotionVerdict
import com.par9uet.jm.dinner.atom.safeArticleUrl
import com.par9uet.jm.dinner.model.DinnerArticle
import com.par9uet.jm.dinner.model.DinnerPage
import com.par9uet.jm.dinner.model.DinnerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

interface DinnerSource {
    suspend fun list(page: Int): DinnerPage
    suspend fun article(id: Int): DinnerArticle
}

class DinnerPromotionBlocked : IllegalStateException("这篇文章包含推广内容，已拦截")

/** 协议字段与 HTML 解析在仓库内收口，UI 不接触 JSON 或漫画模型。 */
class DinnerRepository(
    private val fetch: suspend (String, Map<String, String>) -> String,
    private val imageHost: () -> String,
    private val parser: ArticleHtmlParser,
) : DinnerSource {
    private data class PromotionCheck(val content: String, val gameUrl: String, val blocked: Boolean, val time: Long)
    private val promotionChecks = LinkedHashMap<Int, PromotionCheck>(16, .75f, true)

    override suspend fun list(page: Int): DinnerPage = withContext(Dispatchers.Default) {
        require(page > 0)
        val json = JsonParser.parseString(fetch("blogs", mapOf("page" to "$page", "blog_type" to "dinner"))).asJsonObject
        val rows = requireNotNull(json.getAsJsonArray("list")) { "文章列表格式异常" }
        // 普通正文直接展示；只有存在导流提示但尚未确认的条目才补查详情链接。
        val items = rows.map { it.asJsonObject }.filter { row ->
            when (articlePromotionVerdict(row.string("content"), row.string("game_url"))) {
                ArticlePromotionVerdict.BLOCK -> false
                ArticlePromotionVerdict.ALLOW -> true
                ArticlePromotionVerdict.CHECK_LINKS -> !checkPromotionLinks(row)
            }
        }.map { row ->
            DinnerSummary(row.string("id").toInt(), plain(row.string("title")),
                safeArticleUrl(row.string("photo"), imageHost()), row.string("date"))
        }
        DinnerPage(items, json.string("count").toInt().also { require(it >= 0) }, rawItemCount = rows.size())
    }

    override suspend fun article(id: Int): DinnerArticle = withContext(Dispatchers.Default) {
        require(id > 0)
        val info = fetchInfo(id)
        val content = info.string("content")
        if (articlePromotionVerdict(content, info.string("game_url")) == ArticlePromotionVerdict.BLOCK) throw DinnerPromotionBlocked()
        DinnerArticle(id, plain(info.string("title")), info.string("date"),
            parser.parse(content, imageHost()))
    }

    private suspend fun checkPromotionLinks(row: JsonObject): Boolean {
        val id = row.string("id").toInt()
        val content = row.string("content")
        val gameUrl = row.string("game_url")
        synchronized(promotionChecks) { promotionChecks[id] }?.takeIf {
            it.content == content && it.gameUrl == gameUrl && System.nanoTime() - it.time < 600_000_000_000L
        }?.let { return it.blocked }
        return try {
            val info = withTimeoutOrNull(3_000) { fetchInfo(id) } ?: return false
            val blocked = articlePromotionVerdict(info.string("content"), info.string("game_url")) == ArticlePromotionVerdict.BLOCK
            synchronized(promotionChecks) {
                promotionChecks[id] = PromotionCheck(content, gameUrl, blocked, System.nanoTime())
                if (promotionChecks.size > 128) promotionChecks.remove(promotionChecks.keys.first())
            }
            blocked
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 网络失败不是广告证据；保留条目且不缓存失败，详情打开时仍会重新校验。
            false
        }
    }

    private suspend fun fetchInfo(id: Int): JsonObject {
        val json = JsonParser.parseString(fetch("blog", mapOf("id" to "$id"))).asJsonObject
        return requireNotNull(json.getAsJsonObject("info")) { "文章详情格式异常" }
    }

    private fun plain(value: String): String = Jsoup.parseBodyFragment(value).text()
    private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
}
