package com.par9uet.jm.data.models

/**
 * 搜索结果的年/月筛选，对应官方 `/search` 的 `y` / `m`。
 *
 * 空串 = 不限（官方下拉的「全部年份 / 全部月份」，请求里省略该参数）。
 * 取值是十进制数字串：年 `2017`…`今年`，月 `1`…`12`（与官方 `monthOptions = i + I` 一致，不补零）。
 */
data class SearchComicDateFilter(
    val year: String = "",
    val month: String = "",
) {
    val isActive: Boolean get() = year.isNotBlank() || month.isNotBlank()

    companion object {
        /** 官方年份下拉从 2017 起（`currentYear - 2017 + 1`）。 */
        const val FIRST_YEAR: Int = 2017

        /** 年份候选，新→旧；调用方自行在最前加「全部年份」。 */
        fun yearValues(currentYear: Int): List<String> =
            (currentYear.coerceAtLeast(FIRST_YEAR) downTo FIRST_YEAR).map { it.toString() }

        /** 月份候选 `1`…`12`；调用方自行在最前加「全部月份」。 */
        fun monthValues(): List<String> = (1..12).map { it.toString() }
    }
}
