package com.par9uet.jm.ui.screens.readScreen

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.ui.viewModel.ComicReadViewModel
import com.par9uet.jm.utils.log
import org.koin.compose.getKoin
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 点按翻页的滑动曲线：ease-out-cubic。起手立刻位移、末段收缓，
 * 比瞬时跳转或弹簧都更像"翻过去一页"，也不会在收尾时抖。
 */
private val ReaderTapTurnSpec = tween<Float>(
    durationMillis = 280,
    easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f),
)

/** 目标页相对视口顶部的位移；页不在可视区里（长条页高过视口）时返回 null。 */
private fun LazyListState.offsetOfVisiblePage(target: Int): Float? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.offset?.toFloat()

/**
 * 把目标页滑到视口顶部——**一次**动画完成，不再半屏步进（会拆成两段位移）。
 *
 * - 目标已在组合窗口：按带符号 offset 做一条 ease-out-cubic（上/下同路径）。
 * - 目标还在预取区外：一次 `animateScrollToItem`，避免整屏步进越过上一页后再倒退。
 * - 不检查 `isScrollInProgress`：连点时新动画靠 MutatorMutex 自然抢占。
 */
private suspend fun LazyListState.slideToPageTop(
    target: Int,
    spec: AnimationSpec<Float>,
) {
    if (target == firstVisibleItemIndex) {
        alignToPageTop(target)
        return
    }
    val delta = offsetOfVisiblePage(target)
    if (delta != null && delta != 0f) {
        animateScrollBy(delta, spec)
        alignToPageTop(target)
        return
    }
    if (delta == 0f) {
        alignToPageTop(target)
        return
    }
    // 未进入组合窗口：单次动画跳到目标项（Compose 自带动画，不会分段）。
    animateScrollToItem(target)
    alignToPageTop(target)
}

/**
 * 落点校正：连续点按时新的一段动画会顶掉上一段，被打断的那次可能停在离页首几像素处。
 * 这里只补差值，正常情况下 offset 已经是 0，不会产生可见位移。
 */
private suspend fun LazyListState.alignToPageTop(target: Int) {
    val offset = offsetOfVisiblePage(target) ?: return
    if (offset != 0f) scrollBy(offset)
}

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun ComicScrollRead(
    lazyListState: LazyListState,
    pagerState: PagerState,
    targetIndex: Int,
    zoomState: ReaderZoomState,
    comicReadViewModel: ComicReadViewModel = koinViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get(),
    onUpdateSliderValue: (value: Float) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var currentIndexState by comicReadViewModel.currentIndexState
    val comicPicState by comicReadViewModel.comicPicState.collectAsState()
    val readTapMode by localSettingManager.readTapMode.collectAsState()
    val list = comicPicState.data.orEmpty()
        .distinctBy { "${it.comicId}_${it.originSrc}" }
    val context = LocalContext.current
    // 程序化滚动期间，firstVisibleItemIndex 的观察者必须让位：否则它会把中间页码写回
    // currentIndexState，滑块和页码在动画中途跳一下。用计数而不是布尔——连续点按时
    // 新的一段动画会顶掉上一段，两者的 finally 会短暂重叠。
    val programmaticScrollDepth = remember { mutableIntStateOf(0) }
    // 连点翻页：取消上一段程序化滚动，新目标从当前位置继续，不被旧 Job 的收尾写回覆盖。
    var tapTurnJob by remember { mutableStateOf<Job?>(null) }

    suspend fun runProgrammaticScroll(block: suspend () -> Unit) {
        programmaticScrollDepth.intValue++
        try {
            block()
        } finally {
            programmaticScrollDepth.intValue--
        }
    }

    fun scrollToCurrentPage() {
        if (list.isEmpty()) return
        val target = currentIndexState.coerceIn(0, list.lastIndex)
        currentIndexState = target
        // 先触发目标页解码，滑到时尽量已有位图，减少空白闪帧。
        comicReadViewModel.decodeIndex(target, context)
        tapTurnJob?.cancel()
        tapTurnJob = coroutineScope.launch {
            try {
                runProgrammaticScroll {
                    lazyListState.slideToPageTop(target, ReaderTapTurnSpec)
                    lazyListState.alignToPageTop(target)
                }
                // 只有真正停在目标页才提交 pager/滑块；被连点取消时不写回。
                if (lazyListState.firstVisibleItemIndex == target) {
                    pagerState.scrollToPage(target)
                    onUpdateSliderValue(target.toFloat())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    LaunchedEffect(targetIndex, list.size) {
        if (list.isEmpty()) return@LaunchedEffect
        val target = targetIndex.coerceIn(0, list.lastIndex)
        if (lazyListState.firstVisibleItemIndex != target) {
            runProgrammaticScroll {
                lazyListState.scrollToItem(target)
                pagerState.scrollToPage(target)
            }
        }
    }

    LaunchedEffect(lazyListState) {
        launch {
            snapshotFlow { lazyListState.isScrollInProgress }
                .filter { it }
                .collect {
                    comicReadViewModel.hideToolBar()
                }
        }
        launch {
            snapshotFlow {
                lazyListState.layoutInfo.visibleItemsInfo
                    .takeIf { it.isNotEmpty() }
                    ?.let { it.first().index to it.last().index }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(120)
                .collect { (first, last) ->
                    comicReadViewModel.decodeVisibleRange(first, last, context)
                }
        }
        launch {
            snapshotFlow { lazyListState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .debounce(150)
                .collect {
                    // 读的是状态而不是组合期算好的布尔，否则这里会一直看到旧值。
                    if (programmaticScrollDepth.intValue > 0) return@collect
                    log("lazyListState.firstVisibleItemIndex currentIndexState = $currentIndexState it = $it")
                    if (currentIndexState != it) {
                        currentIndexState = it
                        onUpdateSliderValue(it.toFloat())
                        comicReadViewModel.decodeIndex(currentIndexState, context)
                    }
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            userScrollEnabled = !zoomState.isZoomed,
            modifier = Modifier
                .fillMaxSize()
                .readerGestures(
                    zoomState = zoomState,
                    onNormalTap = { position, viewportSize ->
                        val useSideTap = readTapMode == "side"
                        val isPrevious = if (useSideTap) {
                            position.x < viewportSize.width / 3f
                        } else {
                            position.y < viewportSize.height / 3f
                        }
                        val isNext = if (useSideTap) {
                            position.x > viewportSize.width * 2f / 3f
                        } else {
                            position.y > viewportSize.height * 2f / 3f
                        }

                        when {
                            isPrevious -> {
                                comicReadViewModel.prev(context)
                                scrollToCurrentPage()
                            }

                            isNext -> {
                                comicReadViewModel.next(context)
                                scrollToCurrentPage()
                            }

                            else -> comicReadViewModel.triggerToolBar()
                        }
                    },
                    onZoomedCenterTap = comicReadViewModel::triggerToolBar
                )
        ) {
            items(list, key = {
                "${it.comicId}_${it.originSrc}"
            }) {
                ComicPicImage(
                    comicPicImageState = it,
                        modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            it.aspectRatio
                        )
                )
            }
        }
    }
}
