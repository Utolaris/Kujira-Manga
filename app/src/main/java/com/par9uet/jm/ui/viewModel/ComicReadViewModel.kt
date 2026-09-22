package com.par9uet.jm.ui.viewModel
import com.par9uet.jm.reader.readerPageKey
import com.par9uet.jm.reader.toReaderPage

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.ObserveLocalFavorite
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.reader.molecule.LoadLocalChapter
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.reader.ReaderPageKey
import com.par9uet.jm.reader.readerPrefetchPlan
import com.par9uet.jm.reader.runReaderPrefetchSchedule
import com.par9uet.jm.storage.ReaderPreferences
import com.par9uet.jm.storage.ReadHistoryManager
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.CommonUIState
import com.par9uet.jm.ui.haptics.AppHaptics
import com.par9uet.jm.utils.log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** UI-facing decode result; screens never touch ReaderImagePipeline. */
data class LoadedPageImage(
    val bitmap: ImageBitmap,
    val aspectRatio: Float,
)

class ComicReadViewModel(
    private val comicRepository: ComicRepository,
    private val readerImagePipeline: ReaderImagePipeline,
    private val readerPreferences: ReaderPreferences,
    private val loadLocalChapter: LoadLocalChapter,
    private val toastManager: ToastManager,
    private val readHistoryManager: ReadHistoryManager,
    private val favoriteSession: FavoriteSession,
    private val observeLocalFavorite: ObserveLocalFavorite,
    private val collectFavorite: CollectFavorite,
    private val uncollectFavorites: UncollectFavorites,
    private val downloadManager: DownloadManager,
    private val userManager: com.par9uet.jm.session.UserManager,
    private val readerResumeManager: com.par9uet.jm.storage.ReaderResumeManager,
) : ViewModel() {
    /** Reader screen collects prefs/auth/history through the VM instead of getKoin. */
    val readMode = readerPreferences.readMode
    val readTapMode = readerPreferences.readTapMode
    val authState = userManager.authState
    val readHistoryState = readHistoryManager.readHistoryState

    fun historyKey(comic: Comic?, fallbackId: Int): Int =
        readHistoryManager.historyKey(comic, fallbackId)

    fun lastReadPageIndex(comicKey: Int, chapterId: Int): Int =
        readHistoryManager.lastReadPageIndex(comicKey, chapterId)

    fun saveReadProgress(comicKey: Int, chapterId: Int, pageIndex: Int, pageCount: Int) =
        readHistoryManager.saveReadProgress(comicKey, chapterId, pageIndex, pageCount)

    fun readChapterIds(comicKey: Int): Set<Int> =
        readHistoryManager.readChapterIds(comicKey)

    fun beginReading(chapterId: Int, localOnly: Boolean) =
        readerResumeManager.beginReading(chapterId, localOnly)

    fun markReading(chapterId: Int, localOnly: Boolean) =
        readerResumeManager.markReading(chapterId, localOnly)

    fun endReading(chapterId: Int, localOnly: Boolean) =
        readerResumeManager.endReading(chapterId, localOnly)

    /** Foreground visible-page decode for ComicPicImage; parent passes this as a lambda. */
    suspend fun loadPageImage(state: ComicPicImageState): LoadedPageImage {
        val loaded = readerImagePipeline.loadVisiblePage(state.toReaderPage())
        state.updateAspectRatio(loaded.aspectRatio)
        return LoadedPageImage(loaded.bitmap.asImageBitmap(), loaded.aspectRatio)
    }

    var isShowToolBar = mutableStateOf(false)
    var currentIndexState = mutableIntStateOf(0)
    var loadedComicId = mutableIntStateOf(-1)
    var readHistoryComicId = mutableIntStateOf(-1)
    private val _comicPicState = MutableStateFlow(
        CommonUIState<List<ComicPicImageState>>(
            isLoading = true
        )
    )
    val comicPicState = _comicPicState.asStateFlow()
    private val _comicDetailState = MutableStateFlow(CommonUIState<Comic>())
    val comicDetailState = _comicDetailState.asStateFlow()
    private val _localChapterList = MutableStateFlow<List<ComicChapter>>(emptyList())
    val localChapterList = _localChapterList.asStateFlow()

    val size: Int get() = _comicPicState.value.data?.size ?: 0

    private var prefetchScheduleJob: Job? = null
    private var prefetchScheduleKeys = emptyList<ReaderPageKey>()
    private var prefetchScheduleSourceOnly = false
    private var prefetchScheduleParallelism = 1
    private var foregroundLoadJob: Job? = null
    private var foregroundIndex: Int? = null
    private var lastScheduledIndex: Int? = null
    private var lastDirection = 1
    private var directionStreak = 0
    private var lastDirectionAtMillis = 0L
    private var pageVelocity = 0f

    fun getComicDetail(comicId: Int) {
        observeLocalFavoriteFlag(comicId)
        viewModelScope.launch {
            _comicDetailState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.getComicDetail(comicId)) {
                is NetWorkResult.Error -> {
                    readHistoryComicId.intValue = readHistoryManager.markRead(comicId, comicId)
                    _comicDetailState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success -> {
                    val comic = data.data
                    readHistoryComicId.intValue = readHistoryManager.markRead(comic, comicId)
                    _comicDetailState.update {
                        it.copy(
                            data = comic
                        )
                    }
                }
            }
            _comicDetailState.update {
                it.copy(isLoading = false)
            }
        }
    }

    fun clearComicDetail() {
        localFavoriteJob?.cancel()
        _comicDetailState.update { CommonUIState() }
    }

    fun collect(comicId: Int) {
        updateCollectState(comicId, true)
    }

    fun unCollect(comicId: Int) {
        updateCollectState(comicId, false)
    }

    private var imageLoadJob: Job? = null
    private var imageLoadGeneration = 0L
    private var favoriteActionRunning = false
    private var localFavoriteJob: Job? = null

    /**
     * 阅读器的「已收藏」也只认本地收藏快照，理由同详情页：云端 `is_favorite` 在多端互踢、
     * 换设备或同步落后时会与本地不一致。阅读器会自己拉一次详情，所以在这里同样订阅一次。
     */
    private fun observeLocalFavoriteFlag(albumId: Int) {
        localFavoriteJob?.cancel()
        localFavoriteJob = viewModelScope.launch {
            observeLocalFavorite(albumId).collect { isFavorite ->
                _comicDetailState.update { state ->
                    val data = state.data?.takeIf { it.id == albumId } ?: return@update state
                    if (data.isCollect == isFavorite) {
                        state
                    } else {
                        state.copy(data = data.copy(isCollect = isFavorite))
                    }
                }
            }
        }
    }

    private fun updateCollectState(comicId: Int, targetCollect: Boolean) {
        if (favoriteActionRunning) return
        val comic = _comicDetailState.value.data?.takeIf { it.id == comicId } ?: return
        if (comic.isCollect == targetCollect) return
        val snapshot = favoriteSession.snapshot()
        favoriteActionRunning = true
        viewModelScope.launch {
            try {
                val result = if (targetCollect) {
                    collectFavorite(snapshot, comic)
                } else if (uncollectFavorites(snapshot, listOf(comicId)).succeeded > 0) {
                    NetWorkResult.Success(Unit)
                } else NetWorkResult.Error("取消收藏失败，请重试")
                when (result) {
                    is NetWorkResult.Error -> {
                        toastManager.showAsync(result.message)
                    }
                    is NetWorkResult.Success -> {
                        favoriteSession.withCurrentSession(snapshot) {
                            if (targetCollect) AppHaptics.success()
                            toastManager.showAsync(if (targetCollect) "收藏成功" else "取消收藏成功")
                            _comicDetailState.update {
                                if (it.data?.id == comicId) it.copy(data = it.data.copy(isCollect = targetCollect)) else it
                            }
                        } ?: toastManager.showAsync("登录状态已变化，请重试")
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toastManager.showAsync(error.message ?: "收藏操作失败，请重试")
            } finally {
                favoriteActionRunning = false
            }
        }
    }

    fun getComicPicList(comicId: Int, onSuccess: (() -> Unit)? = null) {
        imageLoadJob?.cancel()
        val generation = ++imageLoadGeneration
        imageLoadJob = viewModelScope.launch {
            _localChapterList.value = emptyList()
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            resetReaderRequests()
            val data = comicRepository.getComicPicList(comicId)
            if (generation != imageLoadGeneration) return@launch
            when (data) {
                is NetWorkResult.Error -> {
                    _comicPicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success -> {
                    val pages = data.data.urls.mapIndexed { index, item ->
                        ComicPicImageState(
                            index,
                            comicId,
                            item,
                            data.data.scrambleId,
                            data.data.speed,
                            imageFetcher = {
                                comicRepository.downloadImageBytes(comicId, index)
                            }
                        )
                    }
                    _comicPicState.update {
                        it.copy(
                            data = pages,
                        )
                    }
                    pages.firstOrNull()?.let { page ->
                        readerImagePipeline.warmImageConnections(page.toReaderPage())
                    }
                    onSuccess?.invoke()
                }
            }
            _comicPicState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun getLocalComicPicList(comicId: Int, onSuccess: (() -> Unit)? = null) {
        imageLoadJob?.cancel()
        val generation = ++imageLoadGeneration
        imageLoadJob = viewModelScope.launch {
            _comicPicState.update { it.copy(isLoading = true, isError = false, errorMsg = "") }
            resetReaderRequests()
            try {
                val chapter = loadLocalChapter(comicId)
                if (generation != imageLoadGeneration) return@launch
                readHistoryComicId.intValue = readHistoryManager.markRead(chapter.groupId, comicId)
                _localChapterList.value = chapter.chapters
                check(chapter.imagePaths.isNotEmpty()) { "未找到本地缓存图片" }
                _comicPicState.update {
                    it.copy(
                        data = chapter.imagePaths.mapIndexed { index, path ->
                            ComicPicImageState(index, comicId, path, Int.MAX_VALUE, "1")
                        },
                        isLoading = false,
                    )
                }
                onSuccess?.invoke()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _comicPicState.update {
                    it.copy(isLoading = false, isError = true, errorMsg = error.message ?: "本地缓存加载失败")
                }
            }
        }
    }

    fun decodeIndex(index: Int, @Suppress("UNUSED_PARAMETER") context: Context) {
        if (size <= 0 || index !in 0 until size) return
        val previous = lastScheduledIndex
        val direction = updateDirection(index)
        val jumpDistance = previous?.let { abs(index - it) } ?: 0
        loadVisible(index)
        schedulePrefetch(index, direction, jumpDistance, index, index)
    }

    fun decodeVisibleRange(
        firstIndex: Int,
        lastIndex: Int,
        @Suppress("UNUSED_PARAMETER") context: Context,
    ) {
        if (size <= 0) return
        val visibleStart = min(firstIndex, lastIndex).coerceIn(0, size - 1)
        val visibleEnd = max(firstIndex, lastIndex).coerceIn(0, size - 1)
        val previous = lastScheduledIndex
        val anchor = when {
            previous == null -> visibleEnd
            visibleEnd > previous -> visibleEnd
            visibleStart < previous -> visibleStart
            lastDirection >= 0 -> visibleEnd
            else -> visibleStart
        }
        val direction = updateDirection(anchor)
        val jumpDistance = previous?.let { abs(anchor - it) } ?: 0
        loadVisible(anchor)
        schedulePrefetch(anchor, direction, jumpDistance, visibleStart, visibleEnd)
    }

    fun prev(context: Context) {
        if (size <= 0) return
        hideToolBar()
        val index = max(0, currentIndexState.intValue - 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    fun next(context: Context) {
        if (size <= 0) return
        hideToolBar()
        val index = min(size - 1, currentIndexState.intValue + 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    private fun updateDirection(index: Int): Int {
        val previous = lastScheduledIndex
        val now = System.currentTimeMillis()
        if (previous == null) {
            lastDirection = 1
            directionStreak = 0
        } else if (index != previous) {
            val direction = if (index > previous) 1 else -1
            val elapsed = (now - lastDirectionAtMillis).coerceAtLeast(1L)
            val instantVelocity = abs(index - previous).toFloat() * 1_000f / elapsed
            pageVelocity = (pageVelocity * 0.65f + instantVelocity * 0.35f).coerceIn(0f, 8f)
            if (direction == lastDirection) {
                directionStreak++
            } else {
                lastDirection = direction
                directionStreak = 1
            }
        }
        lastDirectionAtMillis = now
        lastScheduledIndex = index
        return lastDirection
    }

    private fun loadVisible(index: Int) {
        val page = comicPicState.value.data?.getOrNull(index) ?: return
        if (foregroundLoadJob?.isActive == true && foregroundIndex == index) return
        readerImagePipeline.warmImageConnections(page.toReaderPage())
        foregroundLoadJob?.cancel()
        foregroundIndex = index
        foregroundLoadJob = viewModelScope.launch {
            try {
                readerImagePipeline.loadVisiblePage(page.toReaderPage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("load visible index $index failed: ${e.message}")
            }
        }
    }

    private fun schedulePrefetch(
        index: Int,
        direction: Int,
        jumpDistance: Int,
        visibleStart: Int,
        visibleEnd: Int,
    ) {
        val pages = comicPicState.value.data ?: return
        val prefetchCount = readerPreferences.prefetchCount.value
        val policy = readerImagePipeline.adaptivePrefetchPolicy(
            configuredDistance = prefetchCount,
            jumpDistance = jumpDistance,
            directionStreak = directionStreak,
            pageVelocity = pageVelocity,
            turboMode = prefetchCount >= 5,
        )
        val plannedIndices = readerPrefetchPlan(
            currentPageIndex = index,
            pageCount = pages.size,
            distance = policy.distance,
            direction = direction,
            includeOpposite = readerPreferences.readMode.value != "scroll",
            visibleStart = visibleStart,
            visibleEnd = visibleEnd,
        )
        val plannedPages = plannedIndices.mapNotNull { plannedIndex ->
            pages.getOrNull(plannedIndex)?.let { plannedIndex to it }
        }
        val desiredKeys = plannedPages.map { it.second.readerPageKey() }
        if (
            prefetchScheduleJob?.isActive == true &&
            desiredKeys == prefetchScheduleKeys &&
            policy.sourceOnly == prefetchScheduleSourceOnly &&
            policy.parallelism == prefetchScheduleParallelism
        ) {
            return
        }

        val staleKeys = prefetchScheduleKeys.toSet() - desiredKeys.toSet()
        val previousJob = prefetchScheduleJob
        previousJob?.cancel()
        previousJob?.invokeOnCompletion {
            staleKeys.forEach(readerImagePipeline::cancelPrefetch)
        }
        staleKeys.forEach(readerImagePipeline::cancelPrefetch)

        prefetchScheduleKeys = desiredKeys
        prefetchScheduleSourceOnly = policy.sourceOnly
        prefetchScheduleParallelism = policy.parallelism
        val job = viewModelScope.launch {
            runReaderPrefetchSchedule(plannedPages, policy.parallelism) { (plannedIndex, page) ->
                try {
                    if (policy.sourceOnly) {
                        readerImagePipeline.prefetchPageSource(page.toReaderPage())
                    } else {
                        readerImagePipeline.prefetchPage(page.toReaderPage())
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Speculative failures remain silent; a later visible request retries because
                    // the failed in-flight entry is removed by the pipeline.
                    log("prefetch index $plannedIndex failed: ${e.message}")
                }
            }
        }
        prefetchScheduleJob = job
        job.invokeOnCompletion {
            if (prefetchScheduleJob === job) prefetchScheduleJob = null
        }
    }

    private fun resetReaderRequests() {
        foregroundLoadJob?.cancel()
        foregroundLoadJob = null
        foregroundIndex = null
        prefetchScheduleJob?.cancel()
        prefetchScheduleJob = null
        prefetchScheduleKeys.forEach(readerImagePipeline::cancelPrefetch)
        prefetchScheduleKeys = emptyList()
        prefetchScheduleSourceOnly = false
        prefetchScheduleParallelism = 1
        readerImagePipeline.cancelAllPrefetch()
        lastScheduledIndex = null
        lastDirection = 1
        directionStreak = 0
        lastDirectionAtMillis = 0L
        pageVelocity = 0f
    }

    fun downloadComic(comic: Comic) {
        downloadManager.downloadComic(comic)
    }

    fun downloadChapters(comic: Comic, chapters: List<ComicChapter>) {
        downloadManager.downloadChapters(comic, chapters)
    }

    fun triggerToolBar() {
        isShowToolBar.value = !isShowToolBar.value
        // [GlassDiag] 临时诊断：阅读器 UI 侧的工具栏真值变化，用来对齐下面的玻璃日志。
        log("ReaderDiag", "点屏幕中间 → 工具栏 ${if (isShowToolBar.value) "弹出" else "收起"}")
    }

    fun hideToolBar() {
        if (isShowToolBar.value) {
            log("ReaderDiag", "收起工具栏（翻页/面板连锁）")
        }
        isShowToolBar.value = false
    }

    fun showToolBar() {
        if (!isShowToolBar.value) {
            log("ReaderDiag", "弹出工具栏（跳页/关闭面板后）")
        }
        isShowToolBar.value = true
    }

    override fun onCleared() {
        resetReaderRequests()
    }
}
