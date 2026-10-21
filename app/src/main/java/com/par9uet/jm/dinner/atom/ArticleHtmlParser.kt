package com.par9uet.jm.dinner.atom

import com.par9uet.jm.dinner.model.ArticleBlock
import com.par9uet.jm.dinner.model.ArticleText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** HTML 仅解析为原生阅读块，不执行脚本、内联样式或网页嵌入。 */
class ArticleHtmlParser {
    fun parse(html: String, imageBase: String): List<ArticleBlock> {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script,style,noscript,form,input,button,object,embed").remove()
        return blocks(body.childNodes(), imageBase)
    }

    private fun blocks(nodes: List<Node>, base: String, heading: Int = 0, quote: Boolean = false): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        val runs = mutableListOf<ArticleText>()
        fun flush() {
            if (runs.any { it.text.isNotBlank() }) result += ArticleBlock.Text(linkifyArticleText(runs), heading, quote)
            runs.clear()
        }
        fun visit(node: Node, style: ArticleText = ArticleText("")) {
            when (node) {
                is TextNode -> runs += style.copy(text = node.text().replace('\u00a0', ' '))
                is Element -> when (val tag = node.normalName()) {
                    "br" -> runs += style.copy(text = "\n")
                    "img" -> {
                        flush()
                        val source = sequenceOf("data-src", "data-original", "data-lazy-src", "src")
                            .map { node.attr(it) }.firstOrNull { safeArticleUrl(it, base) != null }
                        val url = source?.let { safeArticleUrl(it, base) }
                        if (url != null) result += ArticleBlock.Image(url, node.attr("alt").ifBlank { "文章插图" },
                            style.link?.let(::normalizeArticleLink))
                    }
                    "hr" -> { flush(); result += ArticleBlock.Rule }
                    "pre" -> { flush(); result += ArticleBlock.Code(node.wholeText()) }
                    "table" -> {
                        flush()
                        result += ArticleBlock.Table(node.select("tr").map { row ->
                            row.children().filter { it.normalName() in setOf("td", "th") }.map { it.text() }
                        }.filter { it.isNotEmpty() })
                    }
                    "iframe", "video", "audio" -> {
                        flush()
                        safeArticleUrl(node.attr("src"), base)?.let {
                            result += ArticleBlock.Text(listOf(ArticleText("打开嵌入内容", link = it)))
                        }
                    }
                    "ul", "ol" -> {
                        flush()
                        var index = node.attr("start").toIntOrNull() ?: 1
                        node.children().forEach { child ->
                            if (child.normalName() == "li") {
                                val prefix = if (tag == "ol") "${index++}. " else "• "
                                val content = blocks(child.childNodes(), base, quote = quote)
                                val first = content.firstOrNull()
                                if (first is ArticleBlock.Text) {
                                    result += first.copy(runs = listOf(ArticleText(prefix)) + first.runs)
                                    result += content.drop(1)
                                } else {
                                    result += ArticleBlock.Text(listOf(ArticleText(prefix)))
                                    result += content
                                }
                            }
                        }
                    }
                    in setOf("p", "div", "section", "article", "figure", "figcaption", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "li") -> {
                        flush()
                        result += blocks(node.childNodes(), base,
                            heading = tag.removePrefix("h").toIntOrNull()?.coerceIn(1, 6) ?: 0,
                            quote = quote || tag == "blockquote")
                    }
                    else -> {
                        val next = style.copy(
                            bold = style.bold || tag in setOf("b", "strong"),
                            italic = style.italic || tag in setOf("i", "em"),
                            code = style.code || tag == "code",
                            strike = style.strike || tag in setOf("s", "del", "strike"),
                            link = if (tag == "a") safeArticleUrl(node.attr("href"), base) else style.link,
                        )
                        node.childNodes().forEach { visit(it, next) }
                    }
                }
            }
        }
        nodes.forEach { visit(it) }
        flush()
        return result
    }
}

/** 相对路径使用文章图床基址；仅允许 HTTP(S)，拒绝 javascript/file/content/data。 */
fun safeArticleUrl(raw: String, base: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.startsWith('#')) return null
    val url = value.toHttpUrlOrNull() ?: base.toHttpUrlOrNull()?.resolve(value) ?: return null
    return url.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
}
