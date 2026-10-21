package com.par9uet.jm.ui.screens.downloadScreen

import com.par9uet.jm.ui.interaction.animateDetailDimension
import com.par9uet.jm.ui.interaction.detailEntryCover
import com.par9uet.jm.ui.interaction.detailEntryContent

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Refresh
import com.par9uet.jm.ui.components.MotionButton as Button
import com.par9uet.jm.ui.components.MotionFilledTonalButton as FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.screens.ComicDetailAdaptiveLayout
import com.par9uet.jm.ui.screens.useDetailColumns
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import com.par9uet.jm.ui.components.ChapterMultiSelectDialog
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.CoverBlurredBackground
import com.par9uet.jm.ui.components.ChapterSingleSelectDialog
import com.par9uet.jm.ui.components.ComicContentTag
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.components.LocalCoverImage
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.screens.tabScreen.FavoritesToolbarDefaults
import com.par9uet.jm.download.model.PdfExportMode
import com.par9uet.jm.ui.viewModel.DownloadExportViewModel
import com.par9uet.jm.ui.viewModel.DownloadComicDetailViewModel
import com.par9uet.jm.utils.formatBytes
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadComicDetailScreen(
    id: Int,
    viewModel: DownloadComicDetailViewModel = koinViewModel(),
    imageLoader: ImageLoader = com.par9uet.jm.coil.currentCoverImageLoader(),
    exportViewModel: DownloadExportViewModel = koinViewModel(),
) {
    val mainNavController = LocalMainNavController.current
    val detailState by viewModel.detailState.collectAsState()
    val remoteImageHost = LocalRemoteImageHost.current
    val scrollState = rememberScrollState()
    val exportState by exportViewModel.state.collectAsState()
    val cachedInfo = exportState.summary
    val exporting = exportState.exporting
    val selectedExportChapterIds = exportState.selectedChapterIds
    var activeDialog by remember { mutableStateOf<DownloadDetailDialog?>(null) }
    com.par9uet.jm.ui.interaction.BlockDetailDrag(activeDialog != null || exporting)
    val tabletEnabled = LocalTabletLayoutEnabled.current
    // Tablet portrait and landscape share one adaptive tree; only the geometry changes.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val wideLayout = tabletEnabled && useDetailColumns(true, windowWidth, windowHeight)
    val openExportDialog: () -> Unit = {
        exportViewModel.selectChapters(detailState.completeItems.map { it.id }.toSet())
        activeDialog = DownloadDetailDialog.ExportChapter
    }
    val openReadChapterDialog: () -> Unit = {
        activeDialog = DownloadDetailDialog.ReadChapter
    }
    val readFirstComplete: () -> Unit = {
        mainNavController.navigate("localComicRead/${detailState.completeItems.first().id}")
    }
    // 底部悬浮玻璃操作条（对齐漫画详情页的观感：悬浮 + 高斯模糊，非通栏底栏）
    val navigationBarInset = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val detailBarHeight = 64.dp
    val detailBarBottomPadding = 8.dp + navigationBarInset

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        exportViewModel.exportTo(uri?.toString())
    }

    LaunchedEffect(id) {
        viewModel.load(id)
    }

    LaunchedEffect(detailState.completeItems, detailState.cachePath) {
        exportViewModel.inspect(detailState.completeItems, detailState.cachePath)
    }

    CommonScaffold(
        title = detailState.title.ifBlank { "缓存详情" },
        variableTopBar = if (tabletEnabled) {
            { statusBarInset ->
                Box(Modifier.fillMaxSize()) {
                    GlassSurface(
                        surfaceId = "download-detail-back",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                start = FavoritesToolbarDefaults.outerMargin,
                                top = statusBarInset + FavoritesToolbarDefaults.outerMargin,
                            )
                            .size(FavoritesToolbarDefaults.controlHeight),
                        style = GlassSurfaceStyle(cornerRadius = 25.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BackIconButton()
                        }
                    }
                }
            }
        } else null,
        overlayContent = {
            // Phones keep a single bar; both tablet orientations retain the same two groups.
            if (detailState.found && detailState.canRead && !tabletEnabled) {
                GlassSurface(
                    surfaceId = "download-detail-actions",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = detailBarBottomPadding)
                        .height(detailBarHeight),
                    style = GlassSurfaceStyle(cornerRadius = 32.dp),
                ) {
                    DownloadDetailBottomActions(
                        modifier = Modifier.fillMaxSize(),
                        isMultiChapter = detailState.isMultiChapter,
                        exporting = exporting,
                        onExport = openExportDialog,
                        onSelectChapter = openReadChapterDialog,
                        onRead = readFirstComplete,
                    )
                }
            }
            // The native glass surfaces stay anchored to the current viewport edges.
            if (detailState.found && detailState.canRead && tabletEnabled) {
                DownloadDetailTabletActions(
                    modifier = Modifier.fillMaxSize().padding(bottom = detailBarBottomPadding),
                    barHeight = detailBarHeight,
                    isMultiChapter = detailState.isMultiChapter,
                    exporting = exporting,
                    onExport = openExportDialog,
                    onSelectChapter = openReadChapterDialog,
                    onRead = readFirstComplete,
                )
            }
            when (activeDialog) {
                DownloadDetailDialog.ReadChapter -> {
                    ChapterSingleSelectDialog(
                        title = "选择缓存章节",
                        chapters = detailState.readableChapters,
                        currentChapterId = null,
                        onDismiss = { activeDialog = null },
                        onSelect = { chapter ->
                            activeDialog = null
                            mainNavController.navigate("localComicRead/${chapter.id}")
                        },
                        surfaceId = "download-detail-read-chapter-glass",
                    )
                }

                DownloadDetailDialog.ExportChapter -> {
                    ChapterMultiSelectDialog(
                        title = "选择导出章节",
                        chapters = detailState.readableChapters,
                        selectedChapterIds = selectedExportChapterIds,
                        onSelectedChange = exportViewModel::selectChapters,
                        onDismiss = { activeDialog = null },
                        confirmText = "合并导出",
                        onConfirm = {
                            activeDialog = null
                            if (exportViewModel.prepareExport(detailState.completeItems, PdfExportMode.Merge)) exportLauncher.launch(null)
                        },
                        secondaryConfirmText = "分章导出",
                        onSecondaryConfirm = {
                            activeDialog = null
                            if (exportViewModel.prepareExport(detailState.completeItems, PdfExportMode.SplitByChapter)) exportLauncher.launch(null)
                        },
                        surfaceId = "download-detail-export-chapter-glass",
                    )
                }

                null -> Unit
            }
        }
    ) { topContentPadding, bottomContentPadding ->
        if (!detailState.found) {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!detailState.loading) {
                    Text(
                        modifier = Modifier.align(Alignment.Center),
                        text = "未找到缓存详情",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@CommonScaffold
        }

        val headerContent: @Composable () -> Unit = {
            Column(Modifier.detailEntryContent(horizontal = true), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    modifier = Modifier.padding(top = 10.dp),
                    text = detailState.title,
                    fontSize = 18.sp,
                    lineHeight = 1.5.em,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val authors = detailState.authorList.filter { it.isNotBlank() && !it.trim().equals("N/A", ignoreCase = true) }
                    if (authors.isEmpty()) {
                        Text("未知作者", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    authors.forEach {
                        key(it) {
                            Text(
                                modifier = Modifier.clickable {
                                    mainNavController.navigate("comicSearchResult/${Uri.encode(it)}")
                                },
                                text = it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 18.sp,
                                lineHeight = 27.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        val detailContent: @Composable () -> Unit = {
            Column(Modifier.detailEntryContent(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (detailState.tagList.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        detailState.tagList.forEach {
                            key(it) {
                                ComicContentTag(it)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.Default.DownloadDone,
                        label = "缓存状态",
                        value = detailState.statusSummary
                    )
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.Default.RemoveRedEye,
                        label = "本地阅读",
                        value = if (detailState.canRead) "可用" else "未完成"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        label = "缓存章节",
                        value = "${detailState.completeChapterCount} / ${detailState.totalChapterCount}"
                    )
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.Default.Storage,
                        label = "图片数量",
                        value = "${cachedInfo?.imageCount ?: 0} 张"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CachedInfoItem(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.FolderZip,
                        label = "占用空间",
                        value = formatBytes(cachedInfo?.totalBytes ?: 0L)
                    )
                }
                if (detailState.isDownloading) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = detailState.groupProgress,
                        label = "downloadProgress"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CachedInfoItem(
                                modifier = Modifier.weight(0.5f),
                                icon = Icons.Default.Speed,
                                label = "下载速度",
                                value = formatSpeed(detailState.downloadSpeed)
                            )
                            Text(
                                text = "${(detailState.groupProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (detailState.hasError) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.retryDownload() },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text("重试下载")
                    }
                }
                Text(
                    text = "缓存时间：${formatTime(detailState.createTime)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "封面：${detailState.coverPath.ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "缓存路径：${detailState.cachePath.ifBlank { detailState.zipPath.ifBlank { "无" } }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "阅读和导出都只显示已缓存完成的章节；重复选择已缓存章节时不会重复下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val coverContent: @Composable (Modifier) -> Unit = { modifier ->
            LocalCover(
                title = detailState.title,
                coverPath = detailState.coverPath,
                remoteHost = remoteImageHost,
                comicId = detailState.remoteCoverComicId,
                imageLoader = imageLoader,
                roundedCorners = tabletEnabled,
                modifier = modifier,
            )
        }
        if (tabletEnabled) {
            Box(Modifier.fillMaxSize()) {
                // 参考漫画详情页横屏：模糊封面垫底，左封面右内容，右侧不再留整列空白。
                CoverBlurredBackground(detailState.remoteCoverComicId, remoteImageHost, Modifier.fillMaxSize())
                ComicDetailAdaptiveLayout(
                    twoColumns = wideLayout,
                    headerTopPadding = topContentPadding + 8.dp,
                    bottomReserved = bottomContentPadding + detailBarHeight + detailBarBottomPadding,
                    scrollState = scrollState,
                    cover = coverContent,
                    metadata = headerContent,
                    body = detailContent,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(
                    top = topContentPadding,
                    bottom = bottomContentPadding + detailBarHeight + detailBarBottomPadding,
                ),
            ) {
                coverContent(Modifier)
                Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    headerContent()
                    detailContent()
                }
            }
        }
    }
}

private enum class DownloadDetailDialog {
    ReadChapter,
    ExportChapter
}

@Composable
private fun DownloadDetailBottomActions(
    modifier: Modifier,
    isMultiChapter: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    onSelectChapter: () -> Unit,
    onRead: () -> Unit,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp),
            onClick = onRead
        ) {
            Text(if (isMultiChapter) "阅读" else "阅读缓存")
        }
        if (isMultiChapter) {
            Button(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = onSelectChapter
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text("章节")
            }
        }
        FilledTonalButton(
            modifier = Modifier.weight(1f),
            enabled = !exporting,
            contentPadding = PaddingValues(horizontal = 8.dp),
            onClick = onExport
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(if (exporting) "导出中" else if (isMultiChapter) "导出" else "导出 PDF")
        }
    }
}

/**
 * 平板底部两组操作，锚定左右下角（参照漫画详情页的「左下阅读 / 右下操作」）：
 * 阅读留在左侧，章节与导出一组居右；两组各自只有自己的玻璃底，中间不铺整条模糊长条。
 */
@Composable
private fun DownloadDetailTabletActions(
    modifier: Modifier = Modifier,
    barHeight: Dp,
    isMultiChapter: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    onSelectChapter: () -> Unit,
    onRead: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val animatedCellWidth by animateDetailDimension(detailTableCellWidth(maxWidth), "download-detail-action-width")
        val cellWidth = animatedCellWidth.coerceAtMost(
            ((maxWidth - 40.dp) / if (isMultiChapter) 3 else 2).coerceAtLeast(0.dp),
        )
        GlassSurface(
            surfaceId = "download-detail-actions-read",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp)
                .width(cellWidth)
                .height(barHeight),
            style = GlassSurfaceStyle(cornerRadius = 32.dp),
        ) {
            Button(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                onClick = onRead,
                shape = CircleShape,
            ) {
                Text(if (isMultiChapter) "阅读" else "阅读缓存")
            }
        }
        GlassSurface(
            surfaceId = "download-detail-actions-export",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp)
                .width(if (isMultiChapter) cellWidth * 2 else cellWidth)
                .height(barHeight),
            style = GlassSurfaceStyle(cornerRadius = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isMultiChapter) {
                    Button(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        onClick = onSelectChapter,
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text("章节")
                    }
                }
                FilledTonalButton(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    enabled = !exporting,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onClick = onExport,
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(if (exporting) "导出中" else if (isMultiChapter) "导出" else "导出 PDF")
                }
            }
        }
    }
}

/** 与漫画详情页一致：每个操作格最多 48dp，左侧阅读组占一格，右侧导出组按按钮数占格。 */
internal fun detailTableCellWidth(viewportWidth: Dp): Dp =
    minOf(160.dp, ((viewportWidth - 40.dp) / 8).coerceAtLeast(0.dp) * 2)

@Composable
private fun LocalCover(
    title: String,
    coverPath: String,
    remoteHost: String,
    comicId: Int,
    imageLoader: ImageLoader,
    roundedCorners: Boolean,
    modifier: Modifier = Modifier,
) {
    var ready by remember(comicId, coverPath) { mutableStateOf(false) }
    val coverModifier = modifier
        .fillMaxWidth()
        .aspectRatio(0.75f)
        .detailEntryCover(ready)
        .then(if (roundedCorners) Modifier.clip(MaterialTheme.shapes.medium) else Modifier)
    if (coverPath.isNotBlank()) {
        LocalCoverImage(
            model = coverPath,
            imageLoader = imageLoader,
            onReady = { ready = it },
            contentDescription = "${title}的封面",
            contentScale = ContentScale.Crop,
            modifier = coverModifier,
        )
    } else {
        JmCoverImage(
            comicId = comicId,
            remoteHost = remoteHost,
            imageLoader = imageLoader,
            onReady = { ready = it },
            contentDescription = "${title}的封面",
            contentScale = ContentScale.Crop,
            modifier = coverModifier,
        )
    }
}

@Composable
private fun CachedInfoItem(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatTime(value: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
}

private fun formatSpeed(bytesPerSec: Float): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / 1_048_576)
        bytesPerSec >= 1024 -> String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1024)
        else -> "${bytesPerSec.toInt()} B/s"
    }
}
