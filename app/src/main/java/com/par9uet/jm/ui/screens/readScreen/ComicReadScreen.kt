package com.par9uet.jm.ui.screens.readScreen
import com.par9uet.jm.ui.components.ReimuLoadingIndicator

import android.app.Activity
import com.par9uet.jm.ui.navigation.HierarchicalBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import com.par9uet.jm.ui.interaction.rememberFavoritePulse
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.layout.widthIn
import com.par9uet.jm.ui.components.MotionButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.session.SessionReadiness

import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassBackdropMode
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.ComicReadViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReadScreen(
    comicId: Int,
    historyId: Int = -1,
    localOnly: Boolean = false,
    comicReadViewModel: ComicReadViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val mainNavController = LocalMainNavController.current
    val isShowToolbar by comicReadViewModel.isShowToolBar
    val size = comicReadViewModel.size
    var currentIndexState by comicReadViewModel.currentIndexState
    val readMode by comicReadViewModel.readMode.collectAsState()
    val authState by comicReadViewModel.authState.collectAsState()
    val comicPicState by comicReadViewModel.comicPicState.collectAsState()
    val comicDetailState by comicReadViewModel.comicDetailState.collectAsState()
    val localChapterList by comicReadViewModel.localChapterList.collectAsState()
    val readHistory by comicReadViewModel.readHistoryState.collectAsState()
    val comic = comicDetailState.data
    // A parent id equal to the chapter id carries no series information; let the VM resolve it.
    val historyKeyHint = historyId.takeIf { it > 0 && it != comicId } ?: -1
    val loading = comicPicState.isLoading
    val initialReaderIndex = if (size > 0) currentIndexState.coerceIn(0, size - 1) else 0
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialReaderIndex)
    val pagerState = rememberPagerState(initialPage = initialReaderIndex) { size }
    val zoomState = rememberReaderZoomState()
    var targetIndex by remember { mutableIntStateOf(initialReaderIndex) }
    var activeDialog by remember { mutableStateOf<ReadPanelDialog?>(null) }
    var selectedCacheChapterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loadedComicId by comicReadViewModel.loadedComicId
    val readHistoryComicId by comicReadViewModel.readHistoryComicId
    val readChapterIds = remember(readHistory, readHistoryComicId) {
        if (readHistoryComicId > 0) {
            comicReadViewModel.readChapterIds(readHistoryComicId)
        } else {
            emptySet()
        }
    }
    val readableChapters = if (localOnly) {
        localChapterList
    } else {
        comic?.comicChapterList.orEmpty()
    }
    val chapterIndex = remember(readableChapters, comicId) {
        readableChapters.indexOfFirst { it.id == comicId }
    }
    val previousChapter = remember(readableChapters, chapterIndex) {
        readableChapters.getOrNull(chapterIndex - 1)
    }
    val nextChapter = remember(readableChapters, chapterIndex) {
        readableChapters.getOrNull(chapterIndex + 1)
    }

    fun navigateToChapter(chapter: ComicChapter?) {
        if (chapter == null) return
        val targetRoute = if (localOnly) {
            "localComicRead/${chapter.id}"
        } else {
            val seriesId = comic?.seriesId?.toIntOrNull()
                ?.takeIf { it > 0 && it != comicId }
                ?: historyKeyHint.takeIf { it > 0 }
                ?: readHistoryComicId.takeIf { it > 0 && it != comicId }
            "comicRead/${chapter.id}" + (seriesId?.let { "?historyId=$it" } ?: "")
        }
        val currentDestinationId = mainNavController.currentBackStackEntry?.destination?.id
        mainNavController.navigate(targetRoute) {
            if (currentDestinationId != null) {
                popUpTo(currentDestinationId) { inclusive = true }
            }
        }
    }

    fun updateIndexFromReader(value: Float) {
        val target = value.toInt().coerceIn(0, maxOf(0, size - 1))
        if (target != currentIndexState) {
            zoomState.reset()
            currentIndexState = target
        }
    }

    fun jumpToIndex(index: Int) {
        if (size <= 0) return

        val target = index.coerceIn(0, size - 1)
        zoomState.reset()
        currentIndexState = target
        targetIndex = target
        comicReadViewModel.decodeIndex(target, context)
        comicReadViewModel.showToolBar()
    }

    // 用户能看到页码的地方只有底部工具栏（ToolsBar 是 currentIndexState 的纯函数，显示错必然是
    // 这个值错）。所以工具栏可见期间就按阅读器的**真实位置**持续对账：无论上游哪条同步链路
    // （翻页观察者、程序化滚动标志）曾经卡住，只要用户看得见工具栏，页码就是对的。
    //
    // 不做成"只在 false→true 那一刻检查"：真实位置和滚动状态都进 key，位置变了、滚动结束了
    // 都会再对一次账，补上"滑动还没停稳就点开工具栏"的那个窗口。
    //
    // 正在滚动（含程序化动画、拖页底滑块）时一律让位：那一刻页码本来就**故意**不等于真实位置——
    // 滑块正被用户拖着、或翻页动画还没走完，此时写回会把动画拽回旧页。
    val readerPage = readerVisiblePage(readMode, lazyListState, pagerState)
    val readerScrolling = readerIsScrolling(readMode, lazyListState, pagerState)
    LaunchedEffect(isShowToolbar, readMode, size, readerPage, readerScrolling) {
        if (!isShowToolbar || size <= 0) return@LaunchedEffect
        // 等一帧再判断：刚被要求跳页时，驱动它的那条链路要到这一帧才把 isScrollInProgress
        // 立起来。不等的话会把"拖到第 3 页"这种正在执行的请求当成页码过期，立刻拽回旧页。
        withFrameNanos { }
        if (readerIsScrolling(readMode, lazyListState, pagerState)) return@LaunchedEffect
        val page = readerVisiblePage(readMode, lazyListState, pagerState)
        if (page !in 0 until size) return@LaunchedEffect
        if (currentIndexState != page) {
            currentIndexState = page
            comicReadViewModel.decodeIndex(page, context)
        }
        // targetIndex 也要跟上：它还停在被卡住的旧页时，任何一次"targetIndex / list.size 变化"
        // 都会让阅读器跳回那一页（ComicPageRead 的 animateScrollToPage、ComicScrollRead 的
        // scrollToItem 都认这个旧目标）。这里写成真实页后，两个消费方都会看到"已在目标页"而空转。
        if (targetIndex != page) {
            targetIndex = page
        }
    }

    LaunchedEffect(comicId, historyId, localOnly) {
        if (localOnly) {
            comicReadViewModel.clearComicDetail()
            comicReadViewModel.getLocalComicPicList(comicId)
        } else {
            comicReadViewModel.getComicDetail(comicId, historyKeyHint)
            comicReadViewModel.getComicPicList(comicId)
        }
    }

    // Metadata and pages arrive independently. Restore only once both the real history key and
    // page count exist; a callback captured before metadata loaded otherwise always restored 0.
    LaunchedEffect(comicId, size, readHistoryComicId, loading) {
        if (!loading && size > 0 && readHistoryComicId > 0) {
            if (loadedComicId != comicId) {
                currentIndexState = comicReadViewModel.lastReadPageIndex(readHistoryComicId, comicId)
                    .coerceIn(0, size - 1)
                loadedComicId = comicId
            }
            targetIndex = currentIndexState.coerceIn(0, size - 1)
            comicReadViewModel.beginReading(comicId, localOnly)
            zoomState.reset()
            comicReadViewModel.decodeIndex(targetIndex, context)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Progress always saves; the resume mark is owned by beginReading/endReading so an
    // explicit back cannot be undone by this dispose-time checkpoint.
    val saveProgress by rememberUpdatedState {
        if (size > 0 && readHistoryComicId > 0 && loadedComicId == comicId) {
            comicReadViewModel.saveReadProgress(readHistoryComicId, comicId, currentIndexState, size)
            comicReadViewModel.markReading(comicId, localOnly)
        }
    }
    DisposableEffect(lifecycleOwner, comicId) {
        val observer = LifecycleEventObserver { _, event ->
            // Process death need not call onDispose. ON_STOP is the durable background checkpoint.
            if (event == Lifecycle.Event.ON_STOP) {
                saveProgress()
                comicReadViewModel.hideToolBar()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveProgress()
        }
    }

    // 数据加载完成后，滚动到恢复的页码
    LaunchedEffect(size, loadedComicId) {
        if (size > 0 && loadedComicId == comicId && targetIndex in 0 until size) {
            if (readMode == "scroll") {
                lazyListState.scrollToItem(targetIndex)
            } else {
                pagerState.scrollToPage(targetIndex)
            }
        }
    }

    val view = LocalView.current
    val controller = remember(view) {
        val window = (context as? Activity)?.window
        if (window == null) {
            null
        } else {
            WindowInsetsControllerCompat(window, view).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
    LaunchedEffect(isShowToolbar) {
        if (isShowToolbar) {
            // Show/hide BOTH system bars together: splitting them dispatches two separate
            // inset changes, which makes the underlying page relayout twice (visible jump
            // when the navigation bar/dock reappears after leaving the reader).
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Restore the system bars BEFORE the reader leaves composition: while this screen is
    // still fully covering the destination, the resulting inset change makes the underlying
    // page relayout invisibly. Restoring in onDispose (after the exit animation) instead let
    // the user watch the page jump when the navigation bar/dock reappeared.
    //
    // HierarchicalBackHandler is composition-owned: it registers once and removes itself on
    // dispose, so chapter jumps / comment / app-lock cannot leave a stale enabled callback
    // holding this chapter's resume state. A raw OnBackPressedCallback added in the function
    // body was re-registered on every recomposition and never removed. It also consumes
    // predictive-back progress so the page does not system-scale while the gesture is held.
    HierarchicalBackHandler {
        controller?.show(WindowInsetsCompat.Type.systemBars())
        // Latch exit BEFORE popBackStack so dispose-time markReading cannot revive it.
        comicReadViewModel.endReading(comicId, localOnly)
        mainNavController.popBackStack()
    }

    // The reader is a full-screen NavHost destination: keep its first frame opaque so the
    // sliding navigation spring never shows the previous screen through the loading state.
    // 阅读器内页启用真实高斯模糊（强度见 GlassMaterialStyle.ReaderBlurRadius，当前与全局一致），
    // 不再走 Frosted 色块。
    GlassCaptureHost(
        modifier = Modifier.fillMaxSize(),
        backdropMode = GlassBackdropMode.Blur,
        sourceContent = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (loading) {
                        ReimuLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (size <= 0) {
                        Text(
                            text = comicPicState.errorMsg.orEmpty().ifBlank { "暂无可阅读图片" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (readMode == "scroll") {
                        ComicScrollRead(
                            lazyListState = lazyListState,
                            pagerState = pagerState,
                            targetIndex = targetIndex,
                            zoomState = zoomState,
                            onUpdateSliderValue = { updateIndexFromReader(it) }
                        )
                    } else {
                        ComicPageRead(
                            lazyListState = lazyListState,
                            pagerState = pagerState,
                            targetIndex = targetIndex,
                            zoomState = zoomState,
                            tapOnly = readMode == "tap",
                            onUpdateSliderValue = { updateIndexFromReader(it) }
                        )
                    }
                }
            }
        },
        overlayContent = {
            if (!loading && size > 0) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp),
                        visible = isShowToolbar,
                        enter = slideInHorizontally(
                            initialOffsetX = { fullWidth -> -fullWidth },
                            animationSpec = tween(durationMillis = 250)
                        ),
                        exit = slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth },
                            animationSpec = tween(durationMillis = 250)
                        )
                    ) {
                        val glassAlpha by transition.animateFloat(
                            transitionSpec = { tween(durationMillis = 250) },
                            label = "readerSideBarGlassAlpha",
                        ) { state ->
                            if (state == EnterExitState.Visible) 1f else 0f
                        }
                        ReadSideBar(
                            comic = comic,
                            localOnly = localOnly,
                            chapterEnabled = readableChapters.isNotEmpty(),
                            surfaceAlpha = glassAlpha,
                            onToggleCollect = {
                                if (authState == SessionReadiness.Unauthenticated) {
                                    mainNavController.navigate("login")
                                } else {
                                    comic?.let { currentComic ->
                                        if (currentComic.isCollect) {
                                            comicReadViewModel.unCollect(currentComic.id)
                                        } else {
                                            comicReadViewModel.collect(currentComic.id)
                                        }
                                    }
                                }
                            },
                            onCache = {
                                comic?.let { currentComic ->
                                    if (currentComic.comicChapterList.isEmpty()) {
                                        comicReadViewModel.downloadComic(currentComic)
                                    } else {
                                        // 面板起来时收起两个工具栏（系统栏跟着一起收）：
                                        // 玻璃弹窗是半透明的，工具栏留在后面会把面板文字压在图标花纹上。
                                        comicReadViewModel.hideToolBar()
                                        selectedCacheChapterIds =
                                            currentComic.comicChapterList.map { it.id }.toSet()
                                        activeDialog = ReadPanelDialog.Cache
                                    }
                                }
                            },
                            onChapterJump = {
                                if (readableChapters.isNotEmpty()) {
                                    comicReadViewModel.hideToolBar()
                                    activeDialog = ReadPanelDialog.Chapter
                                }
                            }
                        )
                    }
                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        visible = isShowToolbar,
                        enter = slideInVertically(
                            initialOffsetY = { fullHeight -> fullHeight },
                            animationSpec = tween(durationMillis = 300)
                        ),
                        exit = slideOutVertically(
                            targetOffsetY = { fullHeight -> fullHeight },
                            animationSpec = tween(durationMillis = 300)
                        )
                    ) {
                        val glassAlpha by transition.animateFloat(
                            transitionSpec = { tween(durationMillis = 300) },
                            label = "readerBottomControlsGlassAlpha",
                        ) { state ->
                            if (state == EnterExitState.Visible) 1f else 0f
                        }
                        ToolsBar(
                            currentIndex = currentIndexState,
                            pageCount = size,
                            previousChapterEnabled = previousChapter != null,
                            nextChapterEnabled = nextChapter != null,
                            showResetZoom = zoomState.isZoomed,
                            surfaceAlpha = glassAlpha,
                            onPreviousChapter = { navigateToChapter(previousChapter) },
                            onNextChapter = { navigateToChapter(nextChapter) },
                            onPageSelected = { jumpToIndex(it) },
                            onResetZoom = { zoomState.reset() }
                        )
                    }
                }
            }
            // 阅读器统一使用渐变磨砂，避免对漫画大图重复捕获并执行 RenderEffect。
            // 面板仍须留在 overlayContent 内，共享宿主的材质、定位和主题更新。
            when (activeDialog) {
                ReadPanelDialog.Cache -> {
                    val currentComic = comic
                    if (currentComic != null) {
                        ChapterCachePickerDialog(
                            chapters = currentComic.comicChapterList,
                            selectedChapterIds = selectedCacheChapterIds,
                            onSelectedChange = { selectedCacheChapterIds = it },
                            // 面板收起后把工具栏放回来：用户是从工具栏进来的，退出时回到同一个状态。
                            onDismiss = {
                                activeDialog = null
                                comicReadViewModel.showToolBar()
                            },
                            onConfirm = {
                                val selectedChapters = currentComic.comicChapterList
                                    .filter { it.id in selectedCacheChapterIds }
                                comicReadViewModel.downloadChapters(currentComic, selectedChapters)
                                activeDialog = null
                                selectedCacheChapterIds = emptySet()
                                comicReadViewModel.showToolBar()
                            }
                        )
                    }
                }

                ReadPanelDialog.Chapter -> {
                    if (readableChapters.isNotEmpty()) {
                        ChapterPickerDialog(
                            title = "跳转章节",
                            chapters = readableChapters,
                            currentChapterId = comicId,
                            readChapterIds = readChapterIds,
                            onDismiss = {
                                activeDialog = null
                                comicReadViewModel.showToolBar()
                            },
                            onSelect = { chapter ->
                                activeDialog = null
                                navigateToChapter(chapter)
                            }
                        )
                    }
                }

                null -> Unit
            }
        },
    )
}

private enum class ReadPanelDialog {
    Cache,
    Chapter
}

/** 阅读器当前真实页码：滚动模式看第一张可见页，其余模式看 Pager 的当前页。 */
private fun readerVisiblePage(
    readMode: String,
    lazyListState: LazyListState,
    pagerState: PagerState,
): Int = if (readMode == "scroll") lazyListState.firstVisibleItemIndex else pagerState.currentPage

/** 阅读器是否正在滚动（含程序化翻页动画）。 */
private fun readerIsScrolling(
    readMode: String,
    lazyListState: LazyListState,
    pagerState: PagerState,
): Boolean = if (readMode == "scroll") {
    lazyListState.isScrollInProgress
} else {
    pagerState.isScrollInProgress
}

@Composable
private fun ChapterCachePickerDialog(
    chapters: List<ComicChapter>,
    selectedChapterIds: Set<Int>,
    onSelectedChange: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val allChapterIds = remember(chapters) { chapters.map { it.id }.toSet() }
    val allSelected = chapters.isNotEmpty() && selectedChapterIds.containsAll(allChapterIds)

    GlassModal(
        visible = true,
        onDismissRequest = onDismiss,
        surfaceId = "chapter-cache-picker-glass",
        modifier = Modifier.widthIn(max = 420.dp),
        style = GlassSurfaceStyle.reader(cornerRadius = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "选择缓存章节", style = MaterialTheme.typography.titleLarge)
            if (chapters.isEmpty()) {
                Text(text = "暂无可选章节")
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectedChange(if (allSelected) emptySet() else allChapterIds)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked ->
                            onSelectedChange(if (checked) allChapterIds else emptySet())
                        }
                    )
                    Text(text = if (allSelected) "取消全选" else "全选")
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                    itemsIndexed(chapters) { index, chapter ->
                        val selected = chapter.id in selectedChapterIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectedChange(
                                        if (selected) {
                                            selectedChapterIds - chapter.id
                                        } else {
                                            selectedChapterIds + chapter.id
                                        }
                                    )
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    onSelectedChange(
                                        if (checked) {
                                            selectedChapterIds + chapter.id
                                        } else {
                                            selectedChapterIds - chapter.id
                                        }
                                    )
                                }
                            )
                            Text(
                                text = chapter.name.ifBlank { "第 ${index + 1} 章" },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "取消")
                }
                Button(
                    onClick = onConfirm,
                    enabled = selectedChapterIds.isNotEmpty()
                ) {
                    Text(text = "开始缓存")
                }
            }
        }
    }
}

@Composable
private fun ReadSideBar(
    comic: Comic?,
    localOnly: Boolean,
    chapterEnabled: Boolean,
    surfaceAlpha: Float = 1f,
    onToggleCollect: () -> Unit,
    onCache: () -> Unit,
    onChapterJump: () -> Unit,
) {
    GlassSurface(
        surfaceId = "reader-left-actions",
        modifier = Modifier.width(82.dp),
        style = GlassSurfaceStyle.reader(cornerRadius = 28.dp),
        surfaceAlpha = surfaceAlpha,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReadSideBarAction(
                icon = if (comic?.isCollect == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = "收藏",
                enabled = !localOnly && comic != null,
                tint = if (comic?.isCollect == true) MaterialTheme.colorScheme.tertiary else null,
                onClick = onToggleCollect,
                pulseOnClick = true,
            )
            ReadSideBarAction(
                icon = Icons.Default.Download,
                label = "缓存",
                enabled = !localOnly && comic != null,
                onClick = onCache
            )
            ReadSideBarAction(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                label = "章节",
                enabled = chapterEnabled,
                onClick = onChapterJump
            )
        }
    }
}

@Composable
private fun ReadSideBarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
    pulseOnClick: Boolean = false,
    onClick: () -> Unit,
) {
    val pulse = rememberFavoritePulse()
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = {
                if (pulseOnClick) pulse.trigger()
                onClick()
            })
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = pulse.scale; scaleY = pulse.scale },
            tint = contentColor
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChapterPickerDialog(
    title: String,
    chapters: List<ComicChapter>,
    currentChapterId: Int?,
    readChapterIds: Set<Int>,
    onDismiss: () -> Unit,
    onSelect: (ComicChapter) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(chapters, currentChapterId) {
        chapters.indexOfFirst { it.id == currentChapterId }
    }
    LaunchedEffect(currentIndex, chapters.size) {
        if (currentIndex >= 0) {
            listState.scrollToItem(currentIndex)
        }
    }
    GlassModal(
        visible = true,
        onDismissRequest = onDismiss,
        surfaceId = "chapter-picker-glass",
        modifier = Modifier.widthIn(max = 420.dp),
        style = GlassSurfaceStyle.reader(cornerRadius = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (chapters.isEmpty()) {
                Text(text = "暂无可选章节")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 360.dp)
                ) {
                    itemsIndexed(chapters) { index, chapter ->
                        val selected = chapter.id == currentChapterId
                        val read = chapter.id in readChapterIds
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(chapter) }
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else if (read) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else if (read) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            tonalElevation = if (selected) 3.dp else 0.dp
                        ) {
                            Text(
                                text = chapter.name.ifBlank { "第 ${index + 1} 章" },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "取消")
                }
            }
        }
    }
}
