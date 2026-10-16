package com.par9uet.jm.ui.screens

import android.content.ClipData
import com.par9uet.jm.ui.navigation.HierarchicalBackHandler
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.session.SessionReadiness
import com.par9uet.jm.ui.components.ChapterMultiSelectDialog
import com.par9uet.jm.ui.components.ComicContentTag
import com.par9uet.jm.ui.components.ComicCoverImage
import com.par9uet.jm.ui.components.ComicRoleTag
import com.par9uet.jm.ui.components.ComicWorkTag
import com.par9uet.jm.ui.components.CoverBlurredBackground
import com.par9uet.jm.ui.components.buildComicDetailText
import com.par9uet.jm.ui.glass.AppGlassTopBarDefaults
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.models.LocalComicDetailLoader
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.screens.tabScreen.FavoritesToolbarDefaults
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.utils.formatAlbumAddTimeDisplay
import com.par9uet.jm.utils.shimmer
import kotlin.math.roundToInt
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinActivityViewModel

internal val ComicDetailHorizontalPadding = 10.dp

/** 详情页内容分区间距（header→tag 组、tag→评论等）。 */
internal val ComicDetailSectionSpacing = 16.dp

@Composable
private fun ComicInfoListItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistChip(
            border = null,
            modifier = Modifier
                .width(50.dp)
                .height(50.dp),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            onClick = {},
            label = {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ComicDetailSkeleton(topContentPadding: Dp) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = topContentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .shimmer()
        )
        Column(
            modifier = Modifier.padding(horizontal = ComicDetailHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(36.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .shimmer()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(34.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .shimmer()
            )
        }
    }
}

@Composable
private fun ComicDetailErrorPage(
    errorMessage: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "加载失败",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = errorMessage ?: "加载失败，请稍后重试",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

/** 标题 + 可点击作者，详情页紧凑 header 与平板元数据列共用。 */
@Composable
private fun ComicTitleAndAuthors(
    comic: Comic,
    onTagSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = Int.MAX_VALUE,
    onTitleClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 标题与作者的字体字号互换：标题 titleMedium, 作者 titleLarge 且用主题主色
        Text(
            text = comic.name,
            style = MaterialTheme.typography.titleMedium,
            lineHeight = 1.5.em,
            fontWeight = FontWeight.Bold,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onTitleClick != null) {
                Modifier.clickable(onClick = onTitleClick)
            } else {
                Modifier
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.authorList.forEach {
                key(it) {
                    Text(
                        modifier = Modifier.clickable(onClick = { onTagSearch(it) }),
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 喜欢/浏览计数：两行纵排、左对齐（浏览量换行与喜欢量对齐）。 */
@Composable
private fun ComicCompactStatsRow(comic: Comic, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ComicStatLine(icon = Icons.Default.Favorite, label = "喜欢", value = comic.likeCount.toString())
        ComicStatLine(icon = Icons.Default.RemoveRedEye, label = "浏览", value = comic.readCount.toString())
    }
}

@Composable
private fun ComicStatLine(icon: ImageVector, label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 标签/角色/作品统一成一个芯片流，逐行动画用。 */
private enum class ComicChipKind { TAG, ROLE, WORK }

private data class ComicChipSpec(val label: String, val kind: ComicChipKind)

/** M3 AssistChip 固定高度与芯片流间距。 */
private val TagChipHeight = 32.dp
private val TagChipSpacing = 5.dp

/** 行入场：透明度走贝塞尔缓动，缩放带轻微 overshoot（贝塞尔曲线 + 弹出感）。 */
private val TagRowEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun overshoot(t: Float, tension: Float = 1.5f): Float {
    if (t >= 1f) return 1f
    if (t <= 0f) return 0f
    val t1 = t - 1f
    return t1 * t1 * ((tension + 1f) * t1 + tension) + 1f
}

/**
 * 对外报告 [heightPx] 高度并裁剪绘制，但子内容仍按原始约束完整测量。
 * 用于「内容自身稳定测量行数、外层高度跑弹簧动画」的组合，避免尺寸反馈回路。
 */
private fun Modifier.fixedClipHeight(heightPx: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(
            minWidth = 0,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        ),
    )
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(width, heightPx) {
        placeable.placeWithLayer(0, 0) {
            clip = true
            shape = RectangleShape
        }
    }
}

/** 加载中的三行标签形骨架占位。 */
@Composable
private fun TagSkeletonRows(modifier: Modifier = Modifier) {
    val rows = remember {
        listOf(
            listOf(72, 110, 64),
            listOf(96, 58),
            listOf(84, 124, 70),
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TagChipSpacing)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TagChipSpacing)) {
                row.forEach { width ->
                    Box(
                        Modifier
                            .width(width.dp)
                            .height(TagChipHeight)
                            .clip(MaterialTheme.shapes.small)
                            .shimmer(),
                    )
                }
            }
        }
    }
}

/**
 * 芯片流的逐行动画布局：
 * - 自定义 Layout 按宽度贪心装箱成行（M3 AssistChip 高度固定，行数即芯片分布）；
 * - [targetRows] 超过 3 时，第 4 行起逐行弹出（每行 130ms 错峰，贝塞尔透明度 + overshoot 缩放）；
 * - 行数经 onSizeChanged 上报（内容测量恒为全高，无动画反馈回路）。
 */
@Composable
private fun AnimatedChipRows(
    chips: List<ComicChipSpec>,
    targetRows: Int,
    onRowsMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val revealed = remember(chips) { Animatable(3f) }
    LaunchedEffect(targetRows) {
        if (targetRows > 3) {
            revealed.animateTo(
                targetRows.toFloat(),
                tween(durationMillis = (targetRows - 3) * 130 + 200, easing = LinearEasing),
            )
        }
    }
    val density = LocalDensity.current
    val stridePx = with(density) { (TagChipHeight + TagChipSpacing).roundToPx() }
    val trailingPx = with(density) { TagChipSpacing.roundToPx() }

    Layout(
        modifier = modifier.onSizeChanged { size ->
            onRowsMeasured((size.height + trailingPx) / stridePx)
        },
        content = {
            chips.forEach { spec ->
                when (spec.kind) {
                    ComicChipKind.TAG -> ComicContentTag(spec.label)
                    ComicChipKind.ROLE -> ComicRoleTag(spec.label)
                    ComicChipKind.WORK -> ComicWorkTag(spec.label)
                }
            }
        },
    ) { measurables, constraints ->
        val hPx = TagChipSpacing.roundToPx()
        val vPx = hPx
        val maxWidth = constraints.maxWidth
        var row = 0
        var x = 0
        val rowHeights = mutableListOf(0)
        val rowOf = IntArray(measurables.size)
        val xPos = IntArray(measurables.size)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = maxWidth)) }
        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > maxWidth) {
                row++
                x = 0
                rowHeights.add(0)
            }
            if (placeable.height > rowHeights[row]) rowHeights[row] = placeable.height
            rowOf[index] = row
            xPos[index] = x
            x += placeable.width + hPx
        }
        val fullHeight = (rowHeights.sum() + vPx * (rowHeights.size - 1)).coerceAtLeast(0)
        val revealedRows = revealed.value
        layout(maxWidth, fullHeight) {
            placeables.forEachIndexed { index, placeable ->
                val r = rowOf[index]
                val progress = (revealedRows - r).coerceIn(0f, 1f)
                if (progress > 0f) {
                    var y = 0
                    for (prev in 0 until r) {
                        y += rowHeights[prev] + vPx
                    }
                    placeable.placeWithLayer(xPos[index], y) {
                        alpha = TagRowEasing.transform(progress)
                        val scale = 0.72f + 0.28f * overshoot(progress)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                }
            }
        }
    }
}

/** 标签/角色/作品芯片区：加载时三行骨架占位，数据到达后逐行弹出、高度弹簧过渡。 */
@Composable
private fun ComicTagSection(
    comic: Comic,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    val chips = remember(comic.id, comic.tagList, comic.roleList, comic.workList) {
        buildList {
            comic.tagList.forEach { add(ComicChipSpec(it, ComicChipKind.TAG)) }
            comic.roleList.forEach { add(ComicChipSpec(it, ComicChipKind.ROLE)) }
            comic.workList.forEach { add(ComicChipSpec(it, ComicChipKind.WORK)) }
        }
    }
    var measuredRows by remember(comic.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val stridePx = with(density) { (TagChipHeight + TagChipSpacing).roundToPx() }
    val trailingPx = with(density) { TagChipSpacing.roundToPx() }
    val skeletonHeightPx = 3 * stridePx - trailingPx

    Box(modifier) {
        if (chips.isEmpty()) {
            if (isLoading) TagSkeletonRows()
            // 加载完成仍无标签：整段收起（高度弹簧到 0 的路径由 chips 非空分支自然覆盖）
        } else {
            val heightAnim = remember(comic.id) { Animatable(skeletonHeightPx.toFloat()) }
            val targetHeightPx = (measuredRows * stridePx - trailingPx).coerceAtLeast(0)
            LaunchedEffect(comic.id, targetHeightPx) {
                if (measuredRows > 0 && heightAnim.targetValue != targetHeightPx.toFloat()) {
                    heightAnim.animateTo(
                        targetHeightPx.toFloat(),
                        spring(dampingRatio = 0.85f, stiffness = 200f),
                    )
                }
            }
            Box(Modifier.fixedClipHeight(heightAnim.value.roundToInt())) {
                AnimatedChipRows(
                    chips = chips,
                    targetRows = measuredRows,
                    onRowsMeasured = { measuredRows = it },
                )
            }
        }
    }
}

/** JM 编号标签：样式与评论区「xxxx 年 x 月 x 日上架」一致（titleMedium + Bold + onSurfaceVariant）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicJmIdLabel(
    comicId: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "JM$comicId",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun ComicMetadataContent(
    comic: Comic,
    onTagSearch: (String) -> Unit,
    onTitleClick: (() -> Unit)? = null,
    isLoading: Boolean = false,
    onCopyId: () -> Unit = {},
    onShowDetail: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ComicTitleAndAuthors(
            comic = comic,
            onTagSearch = onTagSearch,
            onTitleClick = onTitleClick,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComicInfoListItem(
                modifier = Modifier.weight(.5f),
                icon = Icons.Default.Favorite,
                label = "\u559c\u6b22",
                value = comic.likeCount.toString()
            )
            ComicInfoListItem(
                modifier = Modifier.weight(.5f),
                icon = Icons.Default.RemoveRedEye,
                label = "\u6d4f\u89c8",
                value = comic.readCount.toString()
            )
        }
        // JM 编号从封面角标移到这里；点击复制保留，长按打开详情弹窗（原封面角标长按行为）
        ComicJmIdLabel(
            comicId = comic.id,
            onClick = onCopyId,
            onLongClick = onShowDetail,
        )
        ComicTagSection(comic, isLoading = isLoading)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    id: Int,
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
) {
    val mainNavController = LocalMainNavController.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboard.current
    val scrollState = rememberScrollState()
    val comicDetailState by comicDetailViewModel.comicDetailState.collectAsState()
    val collectState by comicDetailViewModel.collectComicState.collectAsState()
    // The activity-scoped ViewModel can still hold another comic for one composition frame while
    // a direct route change is starting. Never render a seed or toolbar title for that old id.
    val requestedComic = comicDetailState.data?.takeIf { it.id == id }
    val authState by comicDetailViewModel.authState.collectAsState()
    val commentLazyPagingItems = remember(id, comicDetailViewModel) {
        comicDetailViewModel.commentPager(id)
    }.collectAsLazyPagingItems()
    // 返回详情页时后台刷新评论：缓存列表先原样显示（VM 侧 per-id cachedIn），
    // 等列表就绪再 refresh，新评论经 paging diff 从顶部插入、旧评论下移。
    // rememberSaveable 跨导航存活，用于区分「首次进入」（等初始加载即可）和「返回」。
    var commentsLoadedOnce by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        snapshotFlow { commentLazyPagingItems.loadState.refresh to commentLazyPagingItems.itemCount }
            .first { (refresh, count) -> refresh is LoadState.NotLoading && count > 0 }
        if (commentsLoadedOnce) {
            commentLazyPagingItems.refresh()
        }
        commentsLoadedOnce = true
    }
    val commentInputFocusRequester = remember { FocusRequester() }
    var showDownloadChapterDialog by remember { mutableStateOf(false) }
    var showTitleDialog by remember { mutableStateOf(false) }
    var selectedChapterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var replyComment by remember(id) { mutableStateOf<Comment?>(null) }
    var detailBottomState by remember(id) { mutableStateOf(DetailBottomModeState()) }
    var commentFocusRequestTick by remember(id) { mutableIntStateOf(0) }
    var showCoverDetailDialog by remember { mutableStateOf(false) }
    var coverDetailInfoText by remember { mutableStateOf("") }
    var coverDetailLoading by remember { mutableStateOf(false) }
    val coverDetailScope = rememberCoroutineScope()
    val comicDetailLoader = LocalComicDetailLoader.current

    fun enterCommentMode() {
        replyComment = null
        detailBottomState = detailBottomState.enterComment()
        commentFocusRequestTick++
    }

    fun enterReplyMode(comment: Comment) {
        replyComment = comment
        detailBottomState = detailBottomState.enterReply(comment.id)
        commentFocusRequestTick++
    }

    fun exitCommentMode() {
        replyComment = null
        detailBottomState = detailBottomState.cancel()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun requireLogin(action: () -> Unit) {
        if (authState == SessionReadiness.Unauthenticated) {
            mainNavController.navigate("login")
        } else {
            action()
        }
    }

    fun searchTag(tag: String) {
        mainNavController.navigate("comicSearchResult/${Uri.encode(tag)}")
    }

    LaunchedEffect(id) {
        if (comicDetailState.data?.id != id) {
            comicDetailViewModel.getComicDetail(id)
        }
    }
    val navigationBarInset = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val statusBarInset = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val detailTopContentPadding = statusBarInset + AppGlassTopBarDefaults.ContentHeight
    // 顶部浮动玻璃钮（50dp）底边到下方 header 内容的间距，与封面块到 tag 组的 16dp 一致。
    val detailHeaderTopPadding = statusBarInset + FavoritesToolbarDefaults.outerMargin +
        FavoritesToolbarDefaults.controlHeight + ComicDetailSectionSpacing
    val detailBarHeight = 64.dp
    val detailBarBottomPadding = 8.dp + navigationBarInset
    // 手机底栏是通栏玻璃条，内容需要让出整条高度；平板底栏是左下阅读 + 右下四操作
    // 两组悬浮玻璃，不必再预留一整条底栏，否则评论滑到底会露出大块主题背景「白框」。
    val isTabletChrome = LocalTabletLayoutEnabled.current
    val detailContentBottomPadding = if (isTabletChrome) {
        detailBarBottomPadding + 16.dp
    } else {
        detailBarHeight + detailBarBottomPadding
    }

    // COMMENT mode consumes Back so it never pops ComicDetail; ACTIONS falls through to
    // normal navigation Back behavior. HierarchicalBackHandler keeps the page whole during
    // a held side-back gesture instead of the system scale-out preview.
    HierarchicalBackHandler(enabled = detailBottomState.mode == DetailBottomMode.COMMENT) {
        exitCommentMode()
    }
    LaunchedEffect(detailBottomState.mode, commentFocusRequestTick) {
        if (detailBottomState.mode == DetailBottomMode.COMMENT) {
            keyboardController?.show()
            commentInputFocusRequester.requestFocus()
        }
    }

    GlassCaptureHost(
        modifier = Modifier.fillMaxSize(),
        sourceContent = {
            when {
                comicDetailState.isError && comicDetailState.data == null -> {
                    ComicDetailErrorPage(
                        errorMessage = comicDetailState.errorMsg,
                        onRetry = { comicDetailViewModel.getComicDetail(id) },
                        onBack = { mainNavController.popBackStack() },
                        modifier = Modifier.padding(top = detailTopContentPadding),
                    )
                }
                requestedComic == null -> {
                    ComicDetailSkeleton(topContentPadding = detailTopContentPadding)
                }
                else -> {
                    val comic = requestedComic
                        // 详情页不需要整页下拉刷新：平板上与内嵌评论 LazyGrid 嵌套滚动会立刻崩溃，
                        // 手机上也只是重复 getComicDetail，已删除 PullToRefreshBox。
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val isTabletLayout = LocalTabletLayoutEnabled.current
                            val viewportHeight = maxHeight
                            if (isTabletLayout) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 整页垫模糊封面背景, 渐变在下半部过渡到纯色,
                                    // 右列滚动内容始终落在接近纯色的区域上。
                                    CoverBlurredBackground(
                                        comicId = comic.id,
                                        remoteHost = LocalRemoteImageHost.current,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = 16.dp,
                                                top = detailHeaderTopPadding,
                                                end = 16.dp,
                                                bottom = 16.dp,
                                            ),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    ) {
                                    ComicCoverImage(
                                        comic = comic,
                                        modifier = Modifier
                                            .widthIn(max = 320.dp)
                                            .weight(0.42f),
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .verticalScroll(scrollState)
                                            .padding(horizontal = ComicDetailHorizontalPadding)
                                            .padding(bottom = detailContentBottomPadding),
                                        verticalArrangement = Arrangement.spacedBy(ComicDetailSectionSpacing),
                                    ) {
                                        ComicMetadataContent(
                                            comic,
                                            ::searchTag,
                                            onTitleClick = { showTitleDialog = true },
                                            isLoading = comicDetailState.isLoading,
                                            onCopyId = {
                                                coverDetailScope.launch {
                                                    clipboard.setClipEntry(
                                                        ClipEntry(ClipData.newPlainText("text", comic.id.toString())),
                                                    )
                                                    comicDetailViewModel.toast("已复制漫画编码：JM${comic.id}")
                                                }
                                            },
                                            onShowDetail = {
                                                coverDetailLoading = true
                                                showCoverDetailDialog = true
                                                coverDetailScope.launch {
                                                    coverDetailInfoText = buildComicDetailText(
                                                        comicDetailLoader,
                                                        comic.id,
                                                    )
                                                    coverDetailLoading = false
                                                }
                                            },
                                        )
                                        ComicCommentContent(
                                            commentLazyPagingItems = commentLazyPagingItems,
                                            authState = authState,
                                            onLogin = { mainNavController.navigate("login") },
                                            onReply = ::enterReplyMode,
                                            // heightIn：短评论不再强行撑满一屏，避免底栏外侧出现空白「白框」。
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = viewportHeight),
                                            listBottomPadding = if (isTabletLayout) {
                                                72.dp + detailBarBottomPadding
                                            } else {
                                                detailBarHeight + detailBarBottomPadding + 24.dp
                                            },
                                            publishDateText = formatAlbumAddTimeDisplay(comic.addTime),
                                        )
                                    }
                                    }
                                }
                            } else {
                                // 手机: animeko 式紧凑 header——小封面 + 标题在右,
                                // 模糊封面背景垫底并延伸到顶栏后面, 随 header 滚走;
                                // 下方标签/评论区保持页面纯色背景。
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(bottom = detailContentBottomPadding),
                                    verticalArrangement = Arrangement.spacedBy(ComicDetailSectionSpacing),
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        CoverBlurredBackground(
                                            comicId = comic.id,
                                            remoteHost = LocalRemoteImageHost.current,
                                            modifier = Modifier.matchParentSize(),
                                        )
                                        Column(
                                            modifier = Modifier.padding(top = detailHeaderTopPadding),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(horizontal = ComicDetailHorizontalPadding + 6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            ) {
                                                ComicCoverImage(
                                                    comic = comic,
                                                    // 120dp 沿对角线放大 1.5 倍: 180dp 宽 / 240dp 高
                                                    modifier = Modifier.width(180.dp),
                                                )
                                                Column(
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    ComicTitleAndAuthors(
                                                        comic = comic,
                                                        onTagSearch = ::searchTag,
                                                        titleMaxLines = 3,
                                                        onTitleClick = { showTitleDialog = true },
                                                    )
                                                    ComicCompactStatsRow(comic)
                                                    // JM 编号移到浏览量下方，点击复制保留
                                                    ComicJmIdLabel(
                                                        comicId = comic.id,
                                                        onClick = {
                                                            coverDetailScope.launch {
                                                                clipboard.setClipEntry(
                                                                    ClipEntry(ClipData.newPlainText("text", comic.id.toString())),
                                                                )
                                                                comicDetailViewModel.toast("已复制漫画编码：JM${comic.id}")
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.padding(horizontal = ComicDetailHorizontalPadding),
                                        verticalArrangement = Arrangement.spacedBy(ComicDetailSectionSpacing),
                                    ) {
                                        ComicTagSection(comic, isLoading = comicDetailState.isLoading)
                                        ComicCommentContent(
                                            commentLazyPagingItems = commentLazyPagingItems,
                                            authState = authState,
                                            onLogin = { mainNavController.navigate("login") },
                                            onReply = ::enterReplyMode,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = viewportHeight),
                                            listBottomPadding = detailBarHeight + detailBarBottomPadding + 24.dp,
                                            publishDateText = formatAlbumAddTimeDisplay(comic.addTime),
                                        )
                                    }
                                }
                            }
                        }

                }
            }
        },
        overlayContent = {
            val comic = requestedComic
            Box(modifier = Modifier.fillMaxSize()) {
                val isTabletLayout = LocalTabletLayoutEnabled.current
                // 顶栏整体去除（对齐 animeko 透明顶栏）：只保留左右两个浮动玻璃钮,
                // 样式复用收藏夹顶栏左上角同步按钮的单钮设计
                // （GlassSurface 50dp + cornerRadius 25dp, FavoritesToolbarDefaults.controlHeight）。
                GlassSurface(
                    surfaceId = "comic-detail-back",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = FavoritesToolbarDefaults.outerMargin, top = statusBarInset + FavoritesToolbarDefaults.outerMargin)
                        .size(FavoritesToolbarDefaults.controlHeight),
                    style = GlassSurfaceStyle(cornerRadius = 25.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BackIconButton()
                    }
                }
                GlassSurface(
                    surfaceId = "comic-detail-comment-toggle",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = FavoritesToolbarDefaults.outerMargin, top = statusBarInset + FavoritesToolbarDefaults.outerMargin)
                        .size(FavoritesToolbarDefaults.controlHeight),
                    style = GlassSurfaceStyle(cornerRadius = 25.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // 48dp 热区与 BackIconButton 一致，两枚图标在玻璃圆内等大居中
                        IconButton(
                            onClick = {
                                when {
                                    detailBottomState.mode == DetailBottomMode.COMMENT -> exitCommentMode()
                                    authState == SessionReadiness.Authenticated -> enterCommentMode()
                                    authState == SessionReadiness.Unauthenticated ->
                                        mainNavController.navigate("login")
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (detailBottomState.mode == DetailBottomMode.COMMENT) Icons.Rounded.Close
                                else Icons.Outlined.ChatBubbleOutline,
                                contentDescription = if (detailBottomState.mode == DetailBottomMode.COMMENT) "返回" else "评论",
                            )
                        }
                    }
                }
                if (comic != null) {
                    AnimatedContent(
                        targetState = detailBottomState.mode,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                                (fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -it / 14 })
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "comic-detail-bottom-mode",
                    ) { targetMode ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = detailBarBottomPadding),
                        ) {
                            when (targetMode) {
                                DetailBottomMode.ACTIONS -> if (isTabletLayout) {
                                    ComicDetailTabletBottomBar(
                                        modifier = Modifier
                                            .fillMaxSize(),
                                        barHeight = detailBarHeight,
                                        comic = comic,
                                        collectEnabled = !collectState.isLoading &&
                                            authState != SessionReadiness.Restoring && authState != SessionReadiness.Unknown,
                                        lastReadChapterId = comicDetailViewModel.lastReadChapterId(comic),
                                        onCollect = {
                                            requireLogin {
                                                if (comic.isCollect) {
                                                    comicDetailViewModel.unCollect(comic.id)
                                                } else {
                                                    // 直接进默认收藏夹（「全部」），不再弹选择框。
                                                    comicDetailViewModel.collect(comic.id)
                                                }
                                            }
                                        },
                                        onRelated = { mainNavController.navigate("comicRelate") },
                                        onDownload = {
                                            if (comic.comicChapterList.isEmpty()) {
                                                comicDetailViewModel.downloadComic(comic)
                                            } else {
                                                selectedChapterIds = comic.comicChapterList.map { it.id }.toSet()
                                                showDownloadChapterDialog = true
                                            }
                                        },
                                        onRead = { targetId -> mainNavController.navigate("comicRead/$targetId") },
                                        onChapters = {
                                            val currentChapterId =
                                                comicDetailViewModel.lastReadChapterId(comic) ?: -1
                                            mainNavController.navigate(
                                                "comicChapter?currentChapterId=$currentChapterId"
                                            )
                                        },
                                    )
                                } else {
                                    ComicDetailBottomBar(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .widthIn(max = 600.dp)
                                            .height(detailBarHeight),
                                        comic = comic,
                                        collectEnabled = !collectState.isLoading &&
                                            authState != SessionReadiness.Restoring && authState != SessionReadiness.Unknown,
                                        lastReadChapterId = comicDetailViewModel.lastReadChapterId(comic),
                                        onCollect = {
                                            requireLogin {
                                                if (comic.isCollect) {
                                                    comicDetailViewModel.unCollect(comic.id)
                                                } else {
                                                    // 直接进默认收藏夹（「全部」），不再弹选择框。
                                                    comicDetailViewModel.collect(comic.id)
                                                }
                                            }
                                        },
                                        onRelated = { mainNavController.navigate("comicRelate") },
                                        onDownload = {
                                            if (comic.comicChapterList.isEmpty()) {
                                                comicDetailViewModel.downloadComic(comic)
                                            } else {
                                                selectedChapterIds = comic.comicChapterList.map { it.id }.toSet()
                                                showDownloadChapterDialog = true
                                            }
                                        },
                                        onRead = { targetId -> mainNavController.navigate("comicRead/$targetId") },
                                        onChapters = {
                                            val currentChapterId =
                                                comicDetailViewModel.lastReadChapterId(comic) ?: -1
                                            mainNavController.navigate(
                                                "comicChapter?currentChapterId=$currentChapterId"
                                            )
                                        },
                                    )
                                }

                                DetailBottomMode.COMMENT -> CommentComposer(
                                    comicId = comic.id,
                                    authState = authState,
                                    replyComment = replyComment,
                                    onCancel = ::exitCommentMode,
                                    commentLazyPagingItems = commentLazyPagingItems,
                                    commentInputFocusRequester = commentInputFocusRequester,
                                    comicDetailViewModel = comicDetailViewModel,
                                    onLogin = { mainNavController.navigate("login") },
                                    onSuccess = ::exitCommentMode,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                        .padding(horizontal = ComicDetailHorizontalPadding),
                                    surfaceIdPrefix = "comic-detail-comment",
                                )
                            }
                    }
                }
            }
            if (showDownloadChapterDialog && comic != null) {
                ChapterMultiSelectDialog(
                    title = "选择缓存章节",
                    chapters = comic.comicChapterList,
                    selectedChapterIds = selectedChapterIds,
                    onSelectedChange = { selectedChapterIds = it },
                    onDismiss = { showDownloadChapterDialog = false },
                    confirmText = "开始缓存",
                    onConfirm = {
                        val selectedChapters = comic.comicChapterList.filter {
                            it.id in selectedChapterIds
                        }
                        comicDetailViewModel.downloadChapters(comic, selectedChapters)
                        showDownloadChapterDialog = false
                    },
                    surfaceId = "comic-detail-chapter-cache-glass",
                )
            }
            GlassModal(
                visible = showTitleDialog,
                onDismissRequest = { showTitleDialog = false },
                surfaceId = "comic-detail-title-glass",
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "标题",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 长按可选中全文复制
                    SelectionContainer {
                        Text(
                            text = requestedComic?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            lineHeight = 1.5.em,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            GlassModal(
                visible = showCoverDetailDialog,
                onDismissRequest = { showCoverDetailDialog = false },
                surfaceId = "comic-detail-cover-detail-glass",
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "漫画详情 (JM${comic?.id ?: ""})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (coverDetailLoading) {
                        Text("加载中...")
                    } else {
                        Text(
                            text = coverDetailInfoText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { showCoverDetailDialog = false }) { Text("关闭") }
                        TextButton(onClick = {
                            coverDetailScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("text", coverDetailInfoText))
                                )
                                comicDetailViewModel.toast("已复制详情信息")
                            }
                        }) { Text("复制") }
                    }
                }
            }
            }
        },
    )
}

/**
 * 平板详情底栏：阅读按钮固定在左下，其余四个操作固定在右下；
 * 两组玻璃表面等宽，避免整条底栏在大屏上被拉成通栏。
 */
@Composable
private fun ComicDetailTabletBottomBar(
    modifier: Modifier = Modifier,
    barHeight: Dp,
    comic: Comic,
    lastReadChapterId: Int?,
    collectEnabled: Boolean,
    onCollect: () -> Unit,
    onRelated: () -> Unit,
    onDownload: () -> Unit,
    onRead: (Int) -> Unit,
    onChapters: () -> Unit,
) {
    val hasChapters = comic.comicChapterList.isNotEmpty()
    val readTargetId = lastReadChapterId
        ?: comic.comicChapterList.firstOrNull()?.id
        ?: comic.id
    val iconCellSize = 48.dp
    val groupWidth = iconCellSize * 4

    Box(modifier = modifier) {
        GlassSurface(
            surfaceId = "comic-detail-actions-read",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp)
                .width(groupWidth)
                .height(barHeight),
            style = GlassSurfaceStyle(cornerRadius = 32.dp),
        ) {
            Button(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                onClick = { onRead(readTargetId) },
                shape = CircleShape,
            ) {
                Text(if (lastReadChapterId != null) "继续阅读" else "阅读")
            }
        }
        GlassSurface(
            surfaceId = "comic-detail-actions",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp)
                .width(groupWidth)
                .height(barHeight),
            style = GlassSurfaceStyle(cornerRadius = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailIconAction(
                    icon = if (comic.isCollect) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (comic.isCollect) "已收藏" else "收藏",
                    tint = if (comic.isCollect) MaterialTheme.colorScheme.tertiary else null,
                    size = iconCellSize,
                    enabled = collectEnabled,
                    onClick = onCollect,
                )
                DetailIconAction(
                    icon = Icons.Default.AutoAwesome,
                    contentDescription = "相关",
                    size = iconCellSize,
                    onClick = onRelated,
                )
                DetailIconAction(
                    icon = Icons.Default.Download,
                    contentDescription = "缓存",
                    size = iconCellSize,
                    onClick = onDownload,
                )
                DetailIconAction(
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = "章节",
                    enabled = hasChapters,
                    size = iconCellSize,
                    onClick = onChapters,
                )
            }
        }
    }
}

@Composable
private fun ComicDetailBottomBar(
    modifier: Modifier = Modifier,
    comic: Comic,
    lastReadChapterId: Int?,
    collectEnabled: Boolean,
    onCollect: () -> Unit,
    onRelated: () -> Unit,
    onDownload: () -> Unit,
    onRead: (Int) -> Unit,
    onChapters: () -> Unit,
) {
    val hasChapters = comic.comicChapterList.isNotEmpty()
    val readTargetId = lastReadChapterId
        ?: comic.comicChapterList.firstOrNull()?.id
        ?: comic.id

    GlassSurface(
        surfaceId = "comic-detail-actions",
        modifier = modifier,
        style = GlassSurfaceStyle(cornerRadius = 32.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val iconCellSize = 48.dp
            val readButtonWidth = (
                maxWidth - 16.dp - iconCellSize * 4
            ).coerceAtLeast(100.dp)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier
                        .width(readButtonWidth)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    onClick = { onRead(readTargetId) },
                    shape = CircleShape,
                ) {
                    Text(if (lastReadChapterId != null) "\u7ee7\u7eed\u9605\u8bfb" else "\u9605\u8bfb")
                }

                DetailIconAction(
                    icon = if (comic.isCollect) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (comic.isCollect) "\u5df2\u6536\u85cf" else "\u6536\u85cf",
                    tint = if (comic.isCollect) MaterialTheme.colorScheme.tertiary else null,
                    size = iconCellSize,
                    enabled = collectEnabled,
                    onClick = onCollect,
                )
                DetailIconAction(
                    icon = Icons.Default.AutoAwesome,
                    contentDescription = "\u76f8\u5173",
                    size = iconCellSize,
                    onClick = onRelated,
                )
                DetailIconAction(
                    icon = Icons.Default.Download,
                    contentDescription = "\u7f13\u5b58",
                    size = iconCellSize,
                    onClick = onDownload,
                )
                DetailIconAction(
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = "\u7ae0\u8282",
                    enabled = hasChapters,
                    size = iconCellSize,
                    onClick = onChapters,
                )
            }
        }
    }
}

@Composable
private fun DetailIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
    size: Dp,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        modifier = Modifier.size(size),
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
        )
    }
}
