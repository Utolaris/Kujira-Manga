package com.par9uet.jm.ui.screens

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.haptics.AppHaptics
import com.par9uet.jm.utils.LogBuffer
import com.par9uet.jm.utils.LogEntry
import com.par9uet.jm.utils.LogExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LongPressFlashMillis = 280L
private const val NewLogHighlightMillis = 1000L

@Composable
fun LogViewerScreen() {
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navigationBarInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    var logs by remember { mutableStateOf(LogBuffer.getLogs()) }
    /** First index that has not been acknowledged at the bottom yet. */
    var seenFrom by remember { mutableIntStateOf(logs.size) }
    var highlightedIndices by remember { mutableStateOf(emptySet<Int>()) }
    var highlightGeneration by remember { mutableIntStateOf(0) }

    fun markBottomSeenAndHighlight() {
        if (logs.size <= seenFrom) {
            seenFrom = logs.size
            return
        }
        val batch = (seenFrom until logs.size).toSet()
        seenFrom = logs.size
        highlightGeneration++
        val generation = highlightGeneration
        highlightedIndices = batch
        scope.launch {
            delay(NewLogHighlightMillis)
            if (highlightGeneration == generation) {
                highlightedIndices = emptySet()
            }
        }
    }

    fun scrollToBottom() {
        if (logs.isEmpty()) return
        markBottomSeenAndHighlight()
        scope.launch {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val next = LogBuffer.getLogs()
            if (next.size < logs.size) {
                seenFrom = next.size
                highlightedIndices = emptySet()
            }
            // Never force-scroll on new logs; the user stays where they are.
            logs = next
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total to lastVisible
        }.collect { (total, lastVisible) ->
            if (total <= 0) return@collect
            val atBottom = logs.isEmpty() || lastVisible >= total - 1
            if (atBottom) {
                markBottomSeenAndHighlight()
            }
        }
    }

    CommonScaffold(
        title = "日志",
        actions = {
            IconButton(
                onClick = {
                    val snapshot = LogBuffer.getLogs()
                    if (snapshot.isEmpty()) {
                        Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    val result = runCatching {
                        LogExporter.export(context, snapshot)
                    }
                    val message = result.fold(
                        onSuccess = { "已导出：${it.absolutePath}" },
                        onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                    )
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                },
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "导出日志 JSON",
                )
            }
        },
        overlayContent = {
            val interactionSource = remember { MutableInteractionSource() }
            GlassSurface(
                surfaceId = "log-viewer-scroll-bottom",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = navigationBarInset + 16.dp)
                    .size(48.dp),
                style = GlassSurfaceStyle(cornerRadius = 24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = ::scrollToBottom,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { topContentPadding, bottomContentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(
                top = topContentPadding + 8.dp,
                bottom = bottomContentPadding + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier.padding(vertical = 32.dp),
                        text = "暂无日志",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(logs, key = { index, _ -> index }) { index, entry ->
                LogItem(
                    entry = entry,
                    highlighted = index in highlightedIndices,
                    onLongClickCopy = {
                        clipboardScope.launch {
                            val copied = runCatching {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("log", entry.formatted)),
                                )
                            }.isSuccess
                            if (copied) {
                                AppHaptics.success()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LogItem(
    entry: LogEntry,
    highlighted: Boolean,
    onLongClickCopy: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    var flashPrimary by remember { mutableStateOf(false) }

    LaunchedEffect(flashPrimary) {
        if (flashPrimary) {
            delay(LongPressFlashMillis)
            flashPrimary = false
        }
    }

    val emphasized = flashPrimary || highlighted
    val defaultBg = when (entry.level) {
        "E" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val defaultText = when (entry.level) {
        "E" -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bgColor = if (emphasized) primary else defaultBg
    val textColor = if (emphasized) onPrimary else defaultText

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    onLongClickCopy()
                    flashPrimary = true
                },
            ),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            text = entry.formatted,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = textColor,
        )
    }
}
