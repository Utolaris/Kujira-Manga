package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.favorites.model.FavoritesFilterPreview
import com.par9uet.jm.ui.components.MotionButton as Button
import com.par9uet.jm.ui.components.MotionIconButton as IconButton
import com.par9uet.jm.ui.components.MotionOutlinedButton as OutlinedButton
import com.par9uet.jm.ui.components.SearchFieldSurface
import com.par9uet.jm.ui.components.searchFieldColors
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.glass.tabletModalWidth
import kotlinx.coroutines.launch

/** Shared solid sheet for dense terms; only the floating actions use a glass backdrop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesFilterSheet(
    tagCountMap: Map<String, Int>,
    authorCountMap: Map<String, Int>,
    selectedTags: Set<String>,
    selectedAuthors: Set<String>,
    tagLogic: TagFilterLogic,
    preview: FavoritesFilterPreview,
    onTagToggle: (String) -> Unit,
    onAuthorToggle: (String) -> Unit,
    onTagLogicChange: (TagFilterLogic) -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = tabletModalWidth() ?: BottomSheetDefaults.SheetMaxWidth,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        FavoritesFilterContent(
            tagCountMap, authorCountMap, selectedTags, selectedAuthors, tagLogic, preview,
            onTagToggle, onAuthorToggle, onTagLogicChange, onConfirm, onClear, onDismiss,
        )
    }
}

/** The sheet has its own window: its floating actions must capture this content, not the grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesFilterContent(
    tagCountMap: Map<String, Int>,
    authorCountMap: Map<String, Int>,
    selectedTags: Set<String>,
    selectedAuthors: Set<String>,
    tagLogic: TagFilterLogic,
    preview: FavoritesFilterPreview,
    onTagToggle: (String) -> Unit,
    onAuthorToggle: (String) -> Unit,
    onTagLogicChange: (TagFilterLogic) -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    var filterQuery by remember { mutableStateOf("") }
    LaunchedEffect(pagerState.currentPage) { filterQuery = "" }

    GlassCaptureHost(
        modifier = modifier.fillMaxSize(),
        sourceContent = {
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "筛选收藏",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭筛选")
                    }
                }
                Text(
                    "标签筛选逻辑",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TagFilterLogic.entries.forEach { logic ->
                        FilterChip(
                            selected = tagLogic == logic,
                            onClick = { onTagLogicChange(logic) },
                            label = {
                                Text(logic.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        )
                    }
                }
                SearchFieldSurface(
                    field = {
                        TextField(
                            modifier = Modifier.weight(1f),
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            singleLine = true,
                            placeholder = {
                                Text(if (pagerState.currentPage == 0) "搜索标签" else "搜索作者")
                            },
                            colors = searchFieldColors(),
                        )
                    },
                    trailing = {
                        if (filterQuery.isNotEmpty()) {
                            IconButton(onClick = { filterQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                )
                Text(
                    when {
                        preview.isLoading -> "正在统计…"
                        preview.count != null -> "匹配 ${preview.count} 部漫画"
                        else -> "暂无法统计"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    listOf("标签 (${tagCountMap.size})", "作者 (${authorCountMap.size})").forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(title) },
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("favorites-filter-pager"),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val authors = page == 1
                    val counts = if (authors) authorCountMap else tagCountMap
                    val query = if (page == pagerState.currentPage) filterQuery.trim() else ""
                    val visibleTerms = remember(counts, query) {
                        if (query.isBlank()) counts else counts.filterKeys { it.contains(query, ignoreCase = true) }
                    }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(top = 12.dp, bottom = 88.dp),
                    ) {
                        if (visibleTerms.isEmpty()) {
                            Text(
                                if (counts.isEmpty()) "当前已加载收藏中没有可筛选的${if (authors) "作者" else "标签"}"
                                else "没有匹配「$query」的${if (authors) "作者" else "标签"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                visibleTerms.forEach { (term, count) ->
                                    val selected = term in if (authors) selectedAuthors else selectedTags
                                    FilterChip(
                                        selected = selected,
                                        onClick = { if (authors) onAuthorToggle(term) else onTagToggle(term) },
                                        label = { Text("$term  $count", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = if (selected) {
                                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                        } else null,
                                        colors = favoriteTermChipColors(authors),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        overlayContent = {
            Box(Modifier.fillMaxSize()) {
                // Same 64dp / 32dp capsule and 48dp buttons as the comic detail actions.
                GlassSurface(
                    surfaceId = "favorites-filter-actions",
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .widthIn(max = 600.dp).fillMaxWidth().height(64.dp),
                    style = GlassSurfaceStyle(cornerRadius = 32.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text("清空") }
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text("确定") }
                    }
                }
            }
        },
    )
}

@Composable
private fun favoriteTermChipColors(author: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = if (author) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = if (author) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = if (author) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = if (author) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
)

