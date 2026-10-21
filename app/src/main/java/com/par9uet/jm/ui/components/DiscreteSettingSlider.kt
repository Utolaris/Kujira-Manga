package com.par9uet.jm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.core.AppHaptics
import kotlin.math.roundToInt

/** Shared cache-budget/grid-column control: tappable labels, round thumb and discrete track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscreteSettingSlider(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    if (labels.size < 2) return
    val selected = selectedIndex.coerceIn(labels.indices)
    var lastHapticIndex by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) { lastHapticIndex = selected }
    fun select(index: Int) {
        if (index == lastHapticIndex) return
        AppHaptics.tick()
        lastHapticIndex = index
        onSelected(index)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val thumbSize = 24.dp
        val labelWidth = (availableWidth / labels.size).coerceAtMost(72.dp)
        val stepWidth = (availableWidth - labelWidth) / labels.lastIndex
        val sliderInset = ((labelWidth - thumbSize) / 2).coerceAtLeast(0.dp)
        val primary = MaterialTheme.colorScheme.primary
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        val inactiveTrack = MaterialTheme.colorScheme.surfaceVariant
        val inactiveStop = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(36.dp)) {
                labels.forEachIndexed { index, label ->
                    Box(Modifier.offset(x = stepWidth * index).width(labelWidth).height(36.dp)
                        .clickable { select(index) }, contentAlignment = Alignment.Center) {
                        Text(label, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = if (availableWidth < 360.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == selected) primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Slider(
                value = selected.toFloat(),
                onValueChange = { select(it.roundToInt().coerceIn(labels.indices)) },
                valueRange = 0f..labels.lastIndex.toFloat(), steps = labels.size - 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = sliderInset).semantics {
                    stateDescription = labels[selected]
                    if (description != null) contentDescription = description
                },
                thumb = {
                    Surface(Modifier.size(thumbSize), shape = CircleShape, color = primary,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface), shadowElevation = 3.dp) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(5.dp).background(onPrimary, CircleShape))
                        }
                    }
                },
                track = { sliderState ->
                    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                        val height = 8.dp.toPx()
                        val top = (size.height - height) / 2f
                        val radius = CornerRadius(height / 2f)
                        val progress = (sliderState.value / labels.lastIndex).coerceIn(0f, 1f)
                        drawRoundRect(inactiveTrack, Offset(0f, top), Size(size.width, height), radius)
                        if (progress > 0f) drawRoundRect(primary, Offset(0f, top), Size(size.width * progress, height), radius)
                        for (index in 1 until labels.lastIndex) {
                            drawCircle(if (index <= sliderState.value) onPrimary else inactiveStop,
                                2.5.dp.toPx(), Offset(size.width * index / labels.lastIndex, size.height / 2f))
                        }
                    }
                },
                colors = SliderDefaults.colors(thumbColor = primary),
            )
        }
    }
}
