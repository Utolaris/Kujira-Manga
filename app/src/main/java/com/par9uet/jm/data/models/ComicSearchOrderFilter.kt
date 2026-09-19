package com.par9uet.jm.data.models

/**
 * 搜索排序。`value` 直接作为上游 `/search` 的 `o` 参数。
 *
 * 命名与文案对齐官方 app v2.1.8（`assets/JsonData.ts` 的 `SearchSortData`）：
 * 官方「最多点击」的 key 是 `mv`，即 MOST_**VIEWED**，与收藏数无关。
 * 该枚举曾把 `mv` 命名为 `MOST_COLLECT_COUNT` 并显示成「最多收藏」，
 * 是同一处误解的两面 —— 改 label 时请一并确认枚举名。
 */
enum class ComicSearchOrderFilter(val value: String, val label: String) {
    NEWEST("mr", "最新"),
    MOST_VIEWED("mv", "最多点击"),
    MOST_PIC_COUNT("mp", "最多图片"),
    MOST_LIKE_COUNT("tf", "最多爱心")
}
