package com.par9uet.jm.dinner.model

data class DinnerSummary(val id: Int, val title: String, val imageUrl: String?, val date: String)
data class DinnerPage(val items: List<DinnerSummary>, val total: Int, val rawItemCount: Int = items.size)
data class DinnerArticle(val id: Int, val title: String, val date: String, val blocks: List<ArticleBlock>)
data class ArticleText(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)
sealed interface ArticleBlock {
    data class Text(val runs: List<ArticleText>, val heading: Int = 0, val quote: Boolean = false) : ArticleBlock
    data class Image(val url: String, val alt: String, val link: String? = null) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Table(val rows: List<List<String>>) : ArticleBlock
    data object Rule : ArticleBlock
}
