package com.par9uet.jm.ui.screens

import com.par9uet.jm.ui.components.MotionCard as Card

import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import com.par9uet.jm.ui.components.MotionIconButton as IconButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import com.par9uet.jm.ui.interaction.rememberRestoringStaggeredGridState
import com.par9uet.jm.ui.models.ListPositionKeyDinner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.par9uet.jm.ui.components.ArticleBlockContent
import com.par9uet.jm.ui.components.ArticleImage
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.adaptiveComicStaggeredGridCells
import com.par9uet.jm.ui.components.comicGridMinCellSize
import com.par9uet.jm.dinner.model.DinnerSummary
import com.par9uet.jm.ui.viewModel.DinnerListState
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.navigation.articleNavigationRoute
import com.par9uet.jm.ui.components.ReimuLoadingIndicator
import com.par9uet.jm.ui.viewModel.DinnerViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun DinnerScreen(viewModel: DinnerViewModel = koinActivityViewModel()) {
    val state by viewModel.list.collectAsState()
    val nav = LocalMainNavController.current
    val misc by viewModel.misc.collectAsState()
    LaunchedEffect(Unit) { if (state.page == 0) viewModel.loadList() }
    CommonScaffold(title = "绅夜食堂", actions = {
        IconButton(onClick = { viewModel.loadList(refresh = true) }, enabled = !state.loading) {
            Icon(Icons.Rounded.Sync, contentDescription = "刷新绅夜食堂")
        }
    }) { top, bottom ->
        DinnerArticleGrid(state, misc.gridColumns.home,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 12.dp, bottom = bottom + 24.dp),
            onOpen = { nav.navigate("dinnerArticle/$it") }, onLoad = { viewModel.loadList() },
            cover = { article, open -> article.imageUrl?.let { url ->
                ArticleImage(url, "", zoomEnabled = false, onClick = open,
                    initialRatio = viewModel.coverRatio(url), onAspectRatio = { viewModel.rememberCoverRatio(url, it) })
            } })
    }
}

/** Natural-height cards fill the shortest lane; list status always occupies the full width. */
@Composable
internal fun DinnerArticleGrid(
    state: DinnerListState,
    columns: Int,
    contentPadding: PaddingValues,
    onOpen: (Int) -> Unit,
    onLoad: () -> Unit,
    cover: @Composable (DinnerSummary, () -> Unit) -> Unit = { article, open ->
        article.imageUrl?.let { ArticleImage(it, "", zoomEnabled = false, onClick = open) }
    },
) {
    val scroll = rememberRestoringStaggeredGridState(ListPositionKeyDinner)
    val latestState by rememberUpdatedState(state)
    val loadMore by rememberUpdatedState(onLoad)
    LaunchedEffect(scroll) {
        snapshotFlow {
            val current = latestState
            // Wait for the new item count to reach layout before considering another page.
            if (current.page > 0 && current.hasMore && !current.loading && current.error == null &&
                scroll.layoutInfo.totalItemsCount == current.items.size + 1 && !scroll.canScrollForward
            ) current.page else null
        }.distinctUntilChanged().filterNotNull().collect { loadMore() }
    }
    val minSize = comicGridMinCellSize()
    LazyVerticalStaggeredGrid(
        columns = adaptiveComicStaggeredGridCells(columns, minSize),
        state = scroll, modifier = Modifier.fillMaxSize().testTag("dinner-grid"), contentPadding = contentPadding,
        verticalItemSpacing = 12.dp, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        staggeredItems(state.items, key = { it.id }, contentType = { "article" }) { article ->
            Card(onClick = { onOpen(article.id) }, modifier = Modifier.fillMaxWidth()) {
                Column {
                    cover(article) { onOpen(article.id) }
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(article.title, fontFamily = FontFamily.Serif, fontSize = 14.sp,
                            lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (article.date.isNotBlank()) Text(article.date, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "list-status", span = StaggeredGridItemSpan.FullLine, contentType = "status") {
            Column(Modifier.fillMaxWidth().testTag("dinner-list-status"), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    state.loading -> ReimuLoadingIndicator()
                    state.error != null -> {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onLoad) { Text("重试") }
                    }
                    state.hasMore -> Spacer(Modifier.height(48.dp))
                    state.items.isEmpty() -> Text("暂无文章")
                    else -> Text("已读到最后一篇", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun DinnerArticleScreen(id: Int, viewModel: DinnerViewModel = koinActivityViewModel()) {
    val state by viewModel.article.collectAsState()
    val nav = LocalMainNavController.current
    val uri = LocalUriHandler.current
    val openLink: (String) -> Unit = { link ->
        val route = articleNavigationRoute(link)
        if (route != null) nav.navigate(route) { launchSingleTop = true }
        else if (link.startsWith("https://") || link.startsWith("http://")) Unit
    }
    val scroll = key(id) { rememberLazyListState() }
    LaunchedEffect(id) { viewModel.openArticle(id) }
    CommonScaffold(title = "绅夜食堂") { top, bottom ->
        val article = state.article?.takeIf { state.id == id }
        if (article != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = top + 28.dp, bottom = bottom + 48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(article.title, fontFamily = FontFamily.Serif, fontSize = 30.sp,
                                lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
                            if (article.date.isNotBlank()) Text(article.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
                        }
                    }
                    items(article.blocks) { block -> ArticleBlockContent(block, onLink = openLink) }
                    if (article.blocks.isEmpty()) item { Text("这篇文章暂时没有正文") }
                }
            }
        } else Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) {
            if (state.loading || state.id != id) ReimuLoadingIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error ?: "文章暂时不可用", color = MaterialTheme.colorScheme.error)
                if (!state.blocked) TextButton(onClick = { viewModel.openArticle(id, retry = true) }) { Text("重新加载文章") }
            }
        }
    }
}
