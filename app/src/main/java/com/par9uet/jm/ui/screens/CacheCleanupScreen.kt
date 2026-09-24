package com.par9uet.jm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.haptics.AppHaptics
import com.par9uet.jm.ui.viewModel.CacheControlState
import com.par9uet.jm.ui.viewModel.CacheCleanupViewModel
import com.par9uet.jm.utils.formatBytes
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun CacheCleanupScreen(
    viewModel: CacheCleanupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    var showClearConfirm by remember { mutableStateOf(false) }

    CommonScaffold(
        title = "缓存控制",
        overlayContent = {
            val usedLabel = formatBytes(state.displayUsedBytes)
            val message = buildString {
                append("将清理约 $usedLabel 缓存。")
                if (state.downloadExempt) {
                    append("已下载漫画不受缓存控制，不会被删除。")
                } else {
                    append("已下载漫画也会被一并清理，之后需重新下载。")
                }
            }
            GlassConfirmDialog(
                visible = showClearConfirm,
                title = "清理缓存",
                message = message,
                confirmText = "清理",
                destructive = true,
                surfaceId = "cache-clean-glass-confirm",
                onConfirm = {
                    showClearConfirm = false
                    viewModel.cleanCache()
                },
                onDismiss = { showClearConfirm = false },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        top = topContentPadding + 16.dp,
                        end = 16.dp,
                        bottom = bottomContentPadding + 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                state.result?.let { message ->
                    Card {
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                TotalBudgetSection(
                    state = state,
                    onBudgetSelected = viewModel::setBudgetMb,
                )

                DownloadExemptRow(
                    exempt = state.downloadExempt,
                    onExemptChange = viewModel::setDownloadExempt,
                )

                CacheDetailList(state = state, colorScheme = colorScheme)

                Button(
                    onClick = { showClearConfirm = true },
                    enabled = !state.cleaning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    if (state.cleaning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text("清理缓存")
                }
            }
        }
    }
}

private fun percentLabel(fraction: Float): String = when {
    fraction <= 0f -> "0%"
    fraction < 0.01f -> "<1%"
    else -> "${(fraction * 100).toInt()}%"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotalBudgetSection(
    state: CacheControlState,
    onBudgetSelected: (Int) -> Unit,
) {
    val stops = state.budgetStops
    if (stops.isEmpty()) return
    val selectedIndex = stops.indexOfFirst { it.mb == state.budgetMb }
        .let { if (it >= 0) it else 1.coerceAtMost(stops.lastIndex) }
    val usageLine = if (state.budgetUnlimited) {
        "已用 ${formatBytes(state.displayUsedBytes)} · 无限制"
    } else {
        "已用 ${formatBytes(state.displayUsedBytes)} / ${formatBytes(state.budgetBytes)}"
    }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    if (selectedIndex != lastHapticIndex) {
        lastHapticIndex = selectedIndex
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "总缓存额度",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = usageLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val availableWidth = maxWidth
                val thumbSize = 24.dp
                val labelWidth = (availableWidth / stops.size).coerceAtMost(72.dp)
                val stepWidth = (availableWidth - labelWidth) / (stops.size - 1).coerceAtLeast(1)
                val sliderInset = (labelWidth - thumbSize) / 2
                val primary = MaterialTheme.colorScheme.primary
                val onPrimary = MaterialTheme.colorScheme.onPrimary
                val inactiveTrack = MaterialTheme.colorScheme.surfaceVariant
                val inactiveStop = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                        stops.forEachIndexed { index, stop ->
                            val selected = index == selectedIndex
                            Box(
                                modifier = Modifier
                                    .offset(x = stepWidth * index)
                                    .width(labelWidth)
                                    .height(36.dp)
                                    .clickable {
                                        if (index != lastHapticIndex) {
                                            AppHaptics.tick()
                                            lastHapticIndex = index
                                        }
                                        onBudgetSelected(stop.mb)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stop.label,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = if (availableWidth < 360.dp) {
                                        MaterialTheme.typography.labelSmall
                                    } else {
                                        MaterialTheme.typography.bodySmall
                                    },
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Slider(
                        value = selectedIndex.toFloat(),
                        onValueChange = { value ->
                            val index = value.roundToInt().coerceIn(stops.indices)
                            if (index != lastHapticIndex) {
                                AppHaptics.tick()
                                lastHapticIndex = index
                                onBudgetSelected(stops[index].mb)
                            }
                        },
                        valueRange = 0f..(stops.size - 1).toFloat(),
                        steps = (stops.size - 2).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = sliderInset),
                        thumb = {
                            Surface(
                                modifier = Modifier.size(thumbSize),
                                shape = CircleShape,
                                color = primary,
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                                shadowElevation = 3.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(modifier = Modifier.size(5.dp).background(onPrimary, CircleShape))
                                }
                            }
                        },
                        track = { sliderState ->
                            Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                                val trackHeight = 8.dp.toPx()
                                val trackTop = (size.height - trackHeight) / 2f
                                val radius = CornerRadius(trackHeight / 2f)
                                val progress = if (stops.size > 1) {
                                    (sliderState.value / stops.lastIndex).coerceIn(0f, 1f)
                                } else 0f
                                drawRoundRect(
                                    color = inactiveTrack,
                                    topLeft = Offset(0f, trackTop),
                                    size = Size(size.width, trackHeight),
                                    cornerRadius = radius,
                                )
                                if (progress > 0f) {
                                    drawRoundRect(
                                        color = primary,
                                        topLeft = Offset(0f, trackTop),
                                        size = Size(size.width * progress, trackHeight),
                                        cornerRadius = radius,
                                    )
                                }
                                for (index in 1 until stops.lastIndex) {
                                    drawCircle(
                                        color = if (index <= sliderState.value) onPrimary else inactiveStop,
                                        radius = 2.5.dp.toPx(),
                                        center = Offset(size.width * index / stops.lastIndex, size.height / 2f),
                                    )
                                }
                            }
                        },
                        colors = SliderDefaults.colors(thumbColor = primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadExemptRow(
    exempt: Boolean,
    onExemptChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "下载漫画不受缓存控制",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "开启后，已下载漫画不占用配额，也不会被自动清理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = exempt, onCheckedChange = onExemptChange)
        }
    }
}

@Composable
private fun CacheDetailList(
    state: CacheControlState,
    colorScheme: ColorScheme,
) {
    val detailSlices = state.pieSlices.filter { it.sizeBytes > 0L && it.fraction > 0f }
    val sliceColors = cacheSlicePaletteColors(colorScheme, detailSlices.size)

    if (detailSlices.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            detailSlices.forEachIndexed { index, slice ->
                val secondary = if (slice.isDownload && state.downloadExempt) "不计入额度" else null
                val rowColor = sliceColors.getOrElse(index) { colorScheme.outline }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = CircleShape,
                        color = rowColor,
                    ) {}
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = slice.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (secondary != null) {
                            Text(
                                text = secondary,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = percentLabel(slice.fraction),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = rowColor,
                        )
                        Text(
                            text = formatBytes(slice.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 按占比名次取调色板实色：主色 → 辅助色 → 第三色 → 错误色；更往后用 outline。
 */
internal fun cacheSlicePaletteColors(colorScheme: ColorScheme, count: Int): List<Color> {
    if (count <= 0) return emptyList()
    val palette = listOf(
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.tertiary,
        colorScheme.error,
    )
    return List(count) { index ->
        if (index < palette.size) palette[index] else colorScheme.outline
    }
}
