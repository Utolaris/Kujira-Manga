package com.par9uet.jm.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.par9uet.jm.data.models.BlockedTagTemplate
import com.par9uet.jm.storage.HistorySearchManager
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.ui.components.ComicSearchHistoryTag
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.SearchExclusionEditor
import com.par9uet.jm.ui.components.SearchFieldSurface
import com.par9uet.jm.ui.components.searchFieldColors
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.SearchViewModel
import com.par9uet.jm.contentfilter.normalizeSearchExcludedTags
import com.par9uet.jm.contentfilter.parseSearchSyntax
import com.par9uet.jm.contentfilter.searchContentWithoutExcludedTags
import com.par9uet.jm.contentfilter.serializeExcludedTags
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComicSearchScreen(
    initialSearchContent: String = "",
    initialExcludedTags: List<String> = emptyList(),
    searchViewModel: SearchViewModel = koinActivityViewModel(),
    historySearchManager: HistorySearchManager = getKoin().get(),
    localSettingManager: LocalSettingManager = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val focusRequester = remember { FocusRequester() }
    val pageLifecycleOwner = LocalLifecycleOwner.current
    val searchComicFilterState by searchViewModel.searchComicFilterState.collectAsState()
    // 从搜索结果页返回时，ViewModel 持有最新搜索参数；首次进入时 ViewModel 为空，回退到 URL 参数
    val effectiveSearchContent = searchComicFilterState.searchContent.ifBlank { initialSearchContent }
    val effectiveExcludedTags = searchComicFilterState.excludedTags.ifEmpty { initialExcludedTags }
    val editableInitialContent = remember(effectiveSearchContent) {
        searchContentWithoutExcludedTags(effectiveSearchContent)
    }
    val textFieldState = rememberTextFieldState(initialText = editableInitialContent)
    var excludedTags by remember(effectiveSearchContent, effectiveExcludedTags) {
        mutableStateOf(
            normalizeSearchExcludedTags(
                parseSearchSyntax(effectiveSearchContent).excludes + effectiveExcludedTags
            )
        )
    }
    val historySearchState by historySearchManager.historySearchState.collectAsState()
    val blockedTagTemplates by localSettingManager.blockedTagTemplates.collectAsState()
    var showSyntaxHelp by remember { mutableStateOf(false) }

    fun addExcludedTag(tag: String) {
        excludedTags = normalizeSearchExcludedTags(excludedTags + tag)
    }

    fun applyTemplate(template: BlockedTagTemplate) {
        excludedTags = normalizeSearchExcludedTags(excludedTags + template.tagList)
    }

    fun search(text: String) {
        val visibleSearchContent = searchContentWithoutExcludedTags(text).trim()
        val inlineExcludedTags = parseSearchSyntax(text).excludes
        val finalExcludedTags = normalizeSearchExcludedTags(excludedTags + inlineExcludedTags)
        if (visibleSearchContent.isBlank()) return

        historySearchManager.addItem(visibleSearchContent)
        // Hand the query to the ViewModel here, on the user's explicit submit, so the result screen
        // always issues a real request even when the query repeats the previous one.
        searchViewModel.submitSearch(visibleSearchContent, finalExcludedTags)
        val encodedSearchContent = Uri.encode(visibleSearchContent)
        val encodedExcludedTags = Uri.encode(serializeExcludedTags(finalExcludedTags))
        mainNavController.navigate(
            "comicSearchResult/$encodedSearchContent?excludedTags=$encodedExcludedTags"
        )
    }

    LaunchedEffect(editableInitialContent) {
        if (textFieldState.text.toString() != editableInitialContent) {
            textFieldState.edit { replace(0, length, editableInitialContent) }
        }
    }

    CommonScaffold(
        title = "搜索",
        actions = {
            IconButton(onClick = { showSyntaxHelp = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = "搜索语法说明",
                )
            }
        },
        overlayContent = {
            SearchSyntaxHelpDialog(
                visible = showSyntaxHelp,
                onDismiss = { showSyntaxHelp = false },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        SearchPageFocusEffect(pageLifecycleOwner)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topContentPadding + 16.dp,
                bottom = bottomContentPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SearchInputCard(
                    textFieldState = textFieldState,
                    focusRequester = focusRequester,
                    onSearch = { search(textFieldState.text.toString()) }
                )
            }
            item {
                SearchExclusionEditor(
                    excludedTags = excludedTags,
                    templates = blockedTagTemplates,
                    onAddTag = { addExcludedTag(it) },
                    onRemoveTag = { tag ->
                        excludedTags = excludedTags.filterNot { it.equals(tag, ignoreCase = true) }
                    },
                    onClearTags = {
                        excludedTags = emptyList()
                    },
                    onApplyTemplate = { applyTemplate(it) },
                    onOpenTemplateSettings = {
                        mainNavController.navigate("blockedTags")
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (historySearchState.isNotEmpty()) {
                        TextButton(onClick = { historySearchManager.clear() }) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (historySearchState.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            text = "暂无搜索历史",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        historySearchState.forEach { tag ->
                            ComicSearchHistoryTag(
                                label = tag,
                                onClick = { search(tag) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索语法说明。二级玻璃弹窗：
 * 手机按 420dp 收口（与其它说明/确认弹窗一致），平板交给 [GlassModal] 的整屏 3/4，
 * 不做成窄条也不铺满整屏。必须留在 [CommonScaffold] 的 `overlayContent` 里，
 * 否则拿不到 `LocalGlassSurfaceRegistry`，会静默退化成纯色面板。
 */
@Composable
private fun SearchSyntaxHelpDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "search-syntax-help-glass",
        modifier = if (LocalTabletLayoutEnabled.current) {
            Modifier
        } else {
            Modifier.widthIn(max = 420.dp)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "搜索的最佳姿势！",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "原神 全彩=原神或全彩；\n原神+全彩=原神且全彩。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "排除标签使用本页的排除功能，或者自定义添加排除模板。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("知道了")
                }
            }
        }
    }
}

@Composable
internal fun SearchPageFocusEffect(pageLifecycleOwner: LifecycleOwner) {
    // Read the owners inside GlassCaptureHost's source composition, where the input lives.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    DisposableEffect(pageLifecycleOwner, focusManager, keyboardController) {
        var inputCleared = false
        fun clearInput() {
            if (inputCleared) return
            inputCleared = true
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) inputCleared = false
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) clearInput()
        }
        pageLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            pageLifecycleOwner.lifecycle.removeObserver(observer)
            clearInput()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInputCard(
    textFieldState: androidx.compose.foundation.text.input.TextFieldState,
    focusRequester: FocusRequester,
    onSearch: () -> Unit
) {
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
    SearchFieldSurface(
        field = {
            androidx.compose.material3.TextField(
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                state = textFieldState,
                lineLimits = TextFieldLineLimits.SingleLine,
                placeholder = {
                    Text(
                        "搜索漫画名 / 作者 / +标签",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = searchFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { onSearch() }
            )
        },
        trailing = {
            // 只保留清空：右侧独立的搜索按钮已去掉，提交走键盘的 IME Search 动作
            // （onKeyboardAction），输入框左侧的放大镜已经表达了「这是搜索」。
            if (textFieldState.text.toString().isNotEmpty()) {
                IconButton(onClick = {
                    textFieldState.edit { replace(0, length, "") }
                }) {
                    Icon(Icons.Rounded.Cancel, contentDescription = "清空")
                }
            }
        },
    )
}
