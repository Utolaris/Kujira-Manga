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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.par9uet.jm.ui.components.buildComicDetailText
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.AppGlassTopBarDefaults
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.models.LocalComicDetailLoader
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.utils.formatAlbumAddTimeDisplay
import com.par9uet.jm.utils.shimmer
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinActivityViewModel

internal val ComicDetailHorizontalPadding = 10.dp

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

@Composable
private fun ComicMetadataContent(
    comic: Comic,
    onTagSearch: (String) -> Unit,
) {
    Text(
        modifier = Modifier.padding(top = 10.dp),
        text = comic.name,
        style = MaterialTheme.typography.titleLarge,
        lineHeight = 1.5.em,
        fontWeight = FontWeight.Bold,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        comic.authorList.forEach {
            key(it) {
                Text(
                    modifier = Modifier.clickable(onClick = { onTagSearch(it) }),
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
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
    if (comic.tagList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.tagList.forEach {
                key(it) {
                    ComicContentTag(it)
                }
            }
        }
    }
    if (comic.roleList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.roleList.forEach {
                key(it) {
                    ComicRoleTag(it)
                }
            }
        }
    }
    if (comic.workList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.workList.forEach {
                key(it) {
                    ComicWorkTag(it)
                }
            }
        }
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
    val commentInputFocusRequester = remember { FocusRequester() }
    var showDownloadChapterDialog by remember { mutableStateOf(false) }
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 16.dp,
                                            top = detailTopContentPadding + 16.dp,
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
                                        showIdChip = true,
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
                                    Column(
                                        modifier = Modifier
                                            .weight(0.58f)
                                            .verticalScroll(scrollState)
                                            .padding(horizontal = ComicDetailHorizontalPadding)
                                            .padding(bottom = detailContentBottomPadding),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        ComicMetadataContent(comic, ::searchTag)
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
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(
                                            top = detailTopContentPadding,
                                            bottom = detailContentBottomPadding,
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    ComicCoverImage(comic = comic, showIdChip = true)
                                    Column(
                                        modifier = Modifier.padding(horizontal = ComicDetailHorizontalPadding),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        ComicMetadataContent(comic, ::searchTag)
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
                AppGlassTopBar(
                    surfaceId = "comic-detail-top-bar",
                    statusBarInset = statusBarInset,
                    modifier = Modifier.align(Alignment.TopCenter),
                    navigationIcon = {
                        BackIconButton()
                    },
                    title = {
                        Text(
                            text = comic?.name.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                when (authState) {
                                    SessionReadiness.Authenticated -> enterCommentMode()
                                    SessionReadiness.Unauthenticated ->
                                        mainNavController.navigate("login")
                                    SessionReadiness.Unknown,
                                    SessionReadiness.Restoring -> Unit
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "评论",
                            )
                        }
                    },
                )
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
