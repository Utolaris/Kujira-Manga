package com.par9uet.jm.cache

enum class CacheArea(val id: String, val title: String, val description: String) {
    COMMON("common", "封面缓存", "Coil 封面加载缓存，清理后封面需重新下载"),
    DOWNLOAD("download", "已下载的漫画", "已下载的漫画图片，清理后需重新下载"),
    READER("reader_pages", "漫画缓存", "阅读页的原图与解码缓存，清理后会重新加载"),
    ALL("total", "全部应用缓存", "包含以上所有缓存和其他临时文件"),
}

data class CacheSize(val area: CacheArea, val sizeBytes: Long)

