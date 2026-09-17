package com.par9uet.jm.ui.components

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.ui.models.ComicDetailLoader
import com.par9uet.jm.ui.models.LocalComicDetailLoader
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import com.par9uet.jm.core.ToastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

/**
 * 封面图。长按 JM 标签触发 [onShowDetail]；调用方须在 GlassCaptureHost overlay 内
 * 自己渲染详情 GlassModal——组件不能自开弹窗，否则 registry 为 null 且 scrim 无法全屏。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicCoverImage(
    comic: Comic,
    modifier: Modifier = Modifier,
    showIdChip: Boolean = false,
    isScrolling: Boolean = false,
    imageLoader: ImageLoader? = null,
    toastManager: ToastManager = getKoin().get(),
    onShowDetail: (() -> Unit)? = null,
) {
    val remoteImageHost = LocalRemoteImageHost.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Box(modifier = modifier) {
        JmCoverImage(
            comicId = comic.id,
            remoteHost = remoteImageHost,
            imageLoader = imageLoader,
            contentDescription = "${comic.name}的封面",
            contentScale = ContentScale.Crop,
            isScrolling = isScrolling,
            modifier = Modifier
                .aspectRatio(3f / 4f)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        if (showIdChip) {
            // 右上角 JM{id} 标签：用 Surface + combinedClickable 实现
            // 不能用 AssistChip，因为它的 onClick 会消费点击事件导致 combinedClickable 不触发
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 10.dp, top = 10.dp)
                    .combinedClickable(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("text", comic.id.toString()))
                                )
                                toastManager.showAsync("已复制漫画编码：${comic.id}")
                            }
                        },
                        onLongClick = { onShowDetail?.invoke() }
                    ),
            ) {
                Text(
                    text = "JM${comic.id}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

internal suspend fun buildComicDetailText(
    detailLoader: ComicDetailLoader?,
    comicId: Int,
): String {
    if (detailLoader == null) return "详情不可用"
    return withContext(Dispatchers.IO) {
        val detail = detailLoader.load(comicId)
        if (detail == null) {
            "获取详情失败"
        } else {
            buildString {
                appendLine("=== 基础信息 ===")
                appendLine("ID: ${detail.id}")
                appendLine("名称: ${detail.name}")
                appendLine("作者: ${detail.authorList.joinToString(", ")}")
                appendLine("简介: ${detail.description.ifBlank { "无" }}")
                appendLine("阅读次数: ${detail.readCount}")
                appendLine("喜欢数: ${detail.likeCount}")
                appendLine("评论数: ${detail.commentCount}")
                appendLine("标签: ${detail.tagList.joinToString(", ").ifBlank { "无" }}")
                appendLine("角色: ${detail.roleList.joinToString(", ").ifBlank { "无" }}")
                appendLine("作品: ${detail.workList.joinToString(", ").ifBlank { "无" }}")
                appendLine()
                appendLine("=== 详情扩展 ===")
                appendLine("已收藏: ${if (detail.isCollect) "是" else "否"}")
                appendLine("系列ID: ${detail.seriesId.ifBlank { "无" }}")
                appendLine("价格: ${detail.price}")
                appendLine("已购买: ${if (detail.isBuy) "是" else "否"}")
                appendLine()
                appendLine("=== 章节 ===")
                detail.comicChapterList.forEachIndexed { i, chapter ->
                    appendLine("${i + 1}. ${chapter.name}")
                }
                appendLine()
                appendLine("=== 相关漫画 ===")
                detail.relateComicList.forEachIndexed { i, related ->
                    appendLine("${i + 1}. JM${related.id} - ${related.name} (${related.authorList.joinToString(", ")})")
                }
            }
        }
    }
}
