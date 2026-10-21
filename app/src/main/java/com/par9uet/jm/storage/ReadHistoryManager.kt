package com.par9uet.jm.storage
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReadHistoryManager(
    private val readHistoryStorage: ReadHistoryStore,
    private val persistScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _readHistoryState = MutableStateFlow<Map<Int, ComicReadHistory>>(emptyMap())
    val readHistoryState = _readHistoryState.asStateFlow()

    private val persistMutex = Mutex()

    fun historyKey(comic: Comic?, fallbackId: Int): Int {
        return comic?.seriesId
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: comic?.id
            ?: fallbackId
    }

    fun markRead(comic: Comic?, chapterId: Int): Int {
        return markRead(historyKey(comic, chapterId), chapterId)
    }

    @Synchronized
    fun markRead(comicKey: Int, chapterId: Int): Int {
        val ready = ensureLoaded()
        val current = _readHistoryState.value.toMutableMap()
        val old = current[comicKey]
        val readIds = (old?.readChapterIds.orEmpty() + chapterId).distinct()
        current[comicKey] = ComicReadHistory(
            lastChapterId = chapterId,
            readChapterIds = readIds,
            lastPageIndex = old?.takeIf { it.lastChapterId == chapterId }?.lastPageIndex ?: 0,
            lastChapterPageCount = old?.takeIf { it.lastChapterId == chapterId }?.lastChapterPageCount ?: 0,
            seriesName = old?.seriesName.orEmpty(),
            seriesAuthorList = old?.seriesAuthorList.orEmpty(),
            seriesTagList = old?.seriesTagList.orEmpty(),
            seriesDescription = old?.seriesDescription.orEmpty(),
            seriesChapterIds = old?.seriesChapterIds.orEmpty(),
        )
        _readHistoryState.update { current }
        if (ready) schedulePersist(current)
        return comicKey
    }

    @Synchronized
    fun saveReadProgress(comicKey: Int, chapterId: Int, pageIndex: Int, pageCount: Int) {
        val ready = ensureLoaded()
        val current = _readHistoryState.value.toMutableMap()
        val old = current[comicKey]
        val readIds = (old?.readChapterIds.orEmpty() + chapterId).distinct()
        current[comicKey] = ComicReadHistory(
            lastChapterId = chapterId,
            readChapterIds = readIds,
            lastPageIndex = pageIndex,
            lastChapterPageCount = pageCount,
            seriesName = old?.seriesName.orEmpty(),
            seriesAuthorList = old?.seriesAuthorList.orEmpty(),
            seriesTagList = old?.seriesTagList.orEmpty(),
            seriesDescription = old?.seriesDescription.orEmpty(),
            seriesChapterIds = old?.seriesChapterIds.orEmpty(),
        )
        _readHistoryState.update { current }
        if (ready) schedulePersist(current)
    }

    /**
     * 记录合集自身的卡片元数据。只接受合集条目：`seriesId` 指向别的作品时它是章节，
     * 不能把章节标题写成合集标题。已有阅读进度原样保留。
     */
    @Synchronized
    fun recordSeries(comic: Comic) {
        val seriesId = comic.seriesId.toIntOrNull()?.takeIf { it > 0 }
        if (seriesId != null && seriesId != comic.id) return
        if (comic.id <= 0 || comic.name.isBlank()) return
        val ready = ensureLoaded()
        val current = _readHistoryState.value.toMutableMap()
        val old = current[comic.id] ?: ComicReadHistory()
        current[comic.id] = old.copy(
            seriesName = comic.name,
            seriesAuthorList = comic.authorList,
            seriesTagList = comic.tagList,
            seriesDescription = comic.description,
            seriesChapterIds = (old.seriesChapterIds + comic.comicChapterList.map { it.id })
                .filter { it > 0 }.distinct(),
        )
        _readHistoryState.update { current }
        if (ready) schedulePersist(current)
    }

    /** 合集卡片元数据；没有记录时返回 null（调用方保留原章节条目）。 */
    fun seriesComic(comicKey: Int): Comic? {
        if (!ensureLoaded()) return null
        val entry = _readHistoryState.value[comicKey] ?: return null
        return entry.toSeriesComic(comicKey)
    }

    /**
     * 历史条目可能是章节；从本机阅读进度或已保存的目录查找它所属的合集卡片。
     * 只在合集元数据存在时替换，避免把章节标题冒充合集。
     */
    fun seriesComicForChapter(chapterId: Int): Comic? {
        if (chapterId <= 0 || !ensureLoaded()) return null
        val parent = _readHistoryState.value.entries.firstOrNull { (key, entry) ->
            key != chapterId && entry.seriesName.isNotBlank() &&
                (entry.lastChapterId == chapterId || chapterId in entry.readChapterIds ||
                    chapterId in entry.seriesChapterIds)
        } ?: return null
        return parent.value.toSeriesComic(parent.key)
    }

    /** 旧进度可能只有父子 id、没有合集标题；仍可用父 id 请求封面。 */
    fun seriesIdForChapter(chapterId: Int): Int? {
        if (chapterId <= 0 || !ensureLoaded()) return null
        return _readHistoryState.value.entries.firstOrNull { (key, entry) ->
            key != chapterId &&
                (entry.lastChapterId == chapterId || chapterId in entry.readChapterIds ||
                    chapterId in entry.seriesChapterIds)
        }?.key
    }

    fun readChapterIds(
        comicKey: Int,
        history: Map<Int, ComicReadHistory> = _readHistoryState.value
    ): Set<Int> {
        return history[comicKey]?.readChapterIds.orEmpty().toSet()
    }

    fun lastReadChapterId(
        comic: Comic,
        history: Map<Int, ComicReadHistory> = _readHistoryState.value
    ): Int? {
        val lastId = history[historyKey(comic, comic.id)]?.lastChapterId?.takeIf { it > 0 }
        if (lastId == null || comic.comicChapterList.isEmpty()) {
            return lastId
        }
        return lastId.takeIf { id -> comic.comicChapterList.any { it.id == id } }
    }

    fun lastReadPageIndex(
        comicKey: Int,
        chapterId: Int,
        history: Map<Int, ComicReadHistory> = _readHistoryState.value
    ): Int {
        val entry = history[comicKey] ?: return 0
        if (entry.lastChapterId != chapterId) return 0
        if (entry.lastChapterPageCount <= 0) return 0
        return entry.lastPageIndex.coerceIn(0, entry.lastChapterPageCount - 1)
    }

    private var loaded = false

    /**
     * Keystore 暂时不可读时返回 false，且不把空 Map 当成已加载基线；
     * 内存里仍保留本会话进度。成功加载时合并磁盘与会话进度，由随后的写回函数持久化。
     */
    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        val disk = readHistoryStorage.getOrNull() ?: return false
        val memory = _readHistoryState.value
        _readHistoryState.value = if (memory.isEmpty()) disk else mergeHistories(disk, memory)
        loaded = true
        return true
    }

    private fun schedulePersist(history: Map<Int, ComicReadHistory>) {
        val snapshot = history.toMap()
        persistScope.launch {
            persistMutex.withLock {
                try {
                    readHistoryStorage.set(snapshot)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log("写入阅读历史失败：" + error.message)
                }
            }
        }
    }

    suspend fun load() {
        log("加载阅读历史")
        ensureLoaded()
        log("阅读历史已加载")
    }
}

/**
 * 会话内存进度覆盖磁盘快照；已读章节并集，避免 Keystore 短暂故障抹掉两边任一侧。
 */
internal fun mergeHistories(
    disk: Map<Int, ComicReadHistory>,
    memory: Map<Int, ComicReadHistory>,
): Map<Int, ComicReadHistory> {
    if (memory.isEmpty()) return disk
    val merged = disk.toMutableMap()
    memory.forEach { (key, mem) ->
        val base = merged[key]
        merged[key] = if (base == null) {
            mem
        } else {
            ComicReadHistory(
                lastChapterId = mem.lastChapterId,
                readChapterIds = (base.readChapterIds + mem.readChapterIds).distinct(),
                lastPageIndex = mem.lastPageIndex,
                lastChapterPageCount = mem.lastChapterPageCount,
                seriesName = mem.seriesName.ifBlank { base.seriesName },
                seriesAuthorList = mem.seriesAuthorList.ifEmpty { base.seriesAuthorList },
                seriesTagList = mem.seriesTagList.ifEmpty { base.seriesTagList },
                seriesDescription = mem.seriesDescription.ifBlank { base.seriesDescription },
                seriesChapterIds = (base.seriesChapterIds + mem.seriesChapterIds).distinct(),
            )
        }
    }
    return merged
}

private fun ComicReadHistory.toSeriesComic(comicKey: Int): Comic? {
    if (seriesName.isBlank()) return null
    return Comic.create(
        id = comicKey,
        name = seriesName,
        authorList = seriesAuthorList,
    ).copy(
        description = seriesDescription,
        tagList = seriesTagList,
    )
}
