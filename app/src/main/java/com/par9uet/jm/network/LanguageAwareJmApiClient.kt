package com.par9uet.jm.network

import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmPhoto
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient

/** 切换语言时清理 SDK 内容缓存；同语言请求仍可并行，连接与会话继续复用。 */
internal class LanguageAwareJmApiClient(
    val client: JmApiClient,
    private val cache: io.github.jukomu.jmcomic.core.cache.CachePool<*, *>,
    private val languageProvider: EmbeddedRequestLanguageProvider,
) {
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private var cachedLanguage = languageProvider.language()

    fun getAlbum(id: String): JmAlbum = withLanguage { client.getAlbum(id) }

    fun getPhoto(id: String): JmPhoto = withLanguage { client.getPhoto(id) }

    private fun <T> withLanguage(block: () -> T): T {
        val language = languageProvider.language()
        val read = lock.readLock()
        val write = lock.writeLock()
        read.lock()
        if (cachedLanguage != language) {
            read.unlock()
            write.lock()
            try {
                if (cachedLanguage != language) {
                    cache.clear()
                    cachedLanguage = language
                }
                // 在释放写锁前降为读锁，旧语言请求不能插入清缓存与发请求之间。
                read.lock()
            } finally {
                write.unlock()
            }
        }
        try {
            return EmbeddedRequestLanguageScope.withLanguage(EmbeddedRequestLanguageProvider { language }, block)
        } finally {
            read.unlock()
        }
    }
}

/** SDK 为同步调用；请求和响应落缓存必须使用入口时的同一语言。嵌套调用沿用外层。 */
internal object EmbeddedRequestLanguageScope {
    private data class Selection(val language: String?)
    private val selected = ThreadLocal<Selection?>()

    fun current(provider: EmbeddedRequestLanguageProvider): String? {
        val selection = selected.get()
        return if (selection != null) selection.language else provider.language()
    }

    fun <T> withLanguage(provider: EmbeddedRequestLanguageProvider, block: () -> T): T {
        if (selected.get() != null) return block()
        selected.set(Selection(provider.language()))
        try {
            return block()
        } finally {
            selected.remove()
        }
    }
}
