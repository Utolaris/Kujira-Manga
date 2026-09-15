package com.par9uet.jm.ui.pagingSource

/** One instance per PagingSource: preserve server order and allow the same page to be retried. */
internal class PageItemDeduplicator<T>(private val keyOf: (T) -> Any) {
    private val firstPageByKey = mutableMapOf<Any, Int>()

    @Synchronized
    fun filter(page: Int, items: List<T>): List<T> {
        val keysInPage = hashSetOf<Any>()
        return items.filter { item ->
            val key = keyOf(item)
            firstPageByKey.getOrPut(key) { page } == page && keysInPage.add(key)
        }
    }
}

// These endpoints accept a page number, not Paging's requested loadSize.
internal const val REMOTE_PAGE_SIZE = 20

/** Use the raw response size, before removing duplicates or excluded tags. */
internal fun isLastRemotePage(page: Int, rawItemCount: Int, total: Int? = null): Boolean =
    rawItemCount == 0 || if (total != null) {
        page.toLong() * REMOTE_PAGE_SIZE >= total
    } else {
        rawItemCount < REMOTE_PAGE_SIZE
    }
