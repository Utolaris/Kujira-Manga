package com.par9uet.jm.ui.navigation

/** 只消费正文解析器已识别的站内目标；普通网页继续交给外部 URI handler。 */
internal fun articleNavigationRoute(link: String): String? {
    val prefix = when {
        link.startsWith("jm://comic/") -> "jm://comic/"
        link.startsWith("jm://article/") -> "jm://article/"
        else -> return null
    }
    val id = link.removePrefix(prefix).toIntOrNull()?.takeIf { it > 0 } ?: return null
    return if (prefix == "jm://comic/") "comicDetail/$id" else "dinnerArticle/$id"
}
