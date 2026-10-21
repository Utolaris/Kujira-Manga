package com.par9uet.jm.data.models

/**
 * 一页漫画列表。
 *
 * [total] 是服务端声明的总条数；为 `null` 表示未取得 total。
 * 调用方按各接口约定判断末页。
 */
data class ComicPage(
    val items: List<Comic>,
    val total: Int? = null,
)
