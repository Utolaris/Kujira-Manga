package com.par9uet.jm.ui.screens

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.interaction.animateDetailDimension
import kotlin.math.roundToInt

internal data class DetailLayoutTargets(
    val coverLeft: Dp,
    val coverWidth: Dp,
    val contentLeft: Dp,
    val contentWidth: Dp,
    val metadataInset: Dp,
)

/** 双栏左右外边距必须一致，否则视觉重心偏左；封面与内容列之间留更宽的呼吸间距。 */
private val DetailOuterMargin = 24.dp
private val DetailColumnGap = 28.dp

internal fun detailLayoutTargets(width: Dp, height: Dp, top: Dp, bottom: Dp, twoColumns: Boolean): DetailLayoutTargets {
    val readingWidth = minOf(width, 840.dp)
    val readingLeft = (width - readingWidth) / 2
    val coverWidth = if (twoColumns) tabletDetailCoverWidth(width, height, top, bottom)
        else compactDetailCoverWidth(true, readingWidth)
    val contentLeft = if (twoColumns) DetailOuterMargin + coverWidth + DetailColumnGap else readingLeft + 10.dp
    return DetailLayoutTargets(
        coverLeft = if (twoColumns) DetailOuterMargin else readingLeft + 16.dp,
        coverWidth = coverWidth,
        contentLeft = contentLeft,
        contentWidth = (if (twoColumns) width - contentLeft - DetailOuterMargin else readingWidth - 20.dp)
            .coerceAtLeast(0.dp),
        metadataInset = if (twoColumns) 0.dp else coverWidth + 20.dp,
    )
}

/** Clamp every intermediate frame to the new viewport, leaving a usable text column. */
internal fun constrainDetailContent(viewport: Dp, left: Dp, width: Dp, endInset: Dp): Pair<Dp, Dp> {
    val available = (viewport - endInset).coerceAtLeast(0.dp)
    val visibleLeft = left.coerceIn(0.dp, (available - minOf(width, 160.dp)).coerceAtLeast(0.dp))
    return visibleLeft to width.coerceIn(0.dp, (available - visibleLeft).coerceAtLeast(0.dp))
}

/** A single cover and scroll column survive rotation. Only their measured geometry changes. */
@Composable
internal fun ComicDetailAdaptiveLayout(
    twoColumns: Boolean,
    headerTopPadding: Dp,
    bottomReserved: Dp,
    scrollState: ScrollState,
    cover: @Composable (Modifier) -> Unit,
    metadata: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val target = detailLayoutTargets(maxWidth, maxHeight, headerTopPadding, bottomReserved, twoColumns)
        val coverLeft = animateDetailDimension(target.coverLeft, "detail-cover-left")
        val coverWidth by animateDetailDimension(target.coverWidth, "detail-cover-width")
        val visibleCover = constrainDetailContent(maxWidth, coverLeft.value, coverWidth, 16.dp)
        val contentLeft by animateDetailDimension(target.contentLeft, "detail-content-left")
        val contentWidth by animateDetailDimension(target.contentWidth, "detail-content-width")
        val metadataInset by animateDetailDimension(target.metadataInset, "detail-metadata-inset")
        val endInset by animateDetailDimension(if (twoColumns) DetailOuterMargin else 10.dp, "detail-content-end")
        val visibleContent = constrainDetailContent(maxWidth, contentLeft, contentWidth, endInset)
        val top by animateDetailDimension(headerTopPadding, "detail-content-top")
        // This distance also controls how far the cover follows the compact column's scroll.
        val compactCoverHeight by animateDetailDimension(
            if (twoColumns) 0.dp else target.coverWidth / .75f, "detail-compact-header-height",
        )
        val bottom by animateDetailDimension(bottomReserved, "detail-content-bottom")
        val compactFraction = if (coverWidth > 0.dp) (compactCoverHeight / (coverWidth / .75f)).coerceIn(0f, 1f) else 0f
        // Anchor the header independently of comment/loading height. Short content leaves
        // unused space below the body instead of moving the cover and introduction down.
        val viewportPx = with(LocalDensity.current) { maxHeight.roundToPx() }
        Layout(
            modifier = Modifier
                .offset { IntOffset(visibleContent.first.roundToPx(), 0) }
                .width(visibleContent.second)
                .height(maxHeight)
                .verticalScroll(scrollState),
            content = { Box { metadata() }; Box { body() } },
        ) { measurables, constraints ->
            val inset = metadataInset.roundToPx().coerceIn(0, (constraints.maxWidth - 80.dp.roundToPx()).coerceAtLeast(0))
            val metadataPlaceable = measurables[0].measure(Constraints.fixedWidth(constraints.maxWidth - inset))
            val bodyPlaceable = measurables[1].measure(Constraints.fixedWidth(constraints.maxWidth))
            val bodyTop = maxOf(metadataPlaceable.height, compactCoverHeight.roundToPx()) + 16.dp.roundToPx()
            val topPx = top.roundToPx()
            val bottomPx = bottom.roundToPx()
            val groupHeight = bodyTop + bodyPlaceable.height
            // verticalScroll may retain the viewport's minimum height. Honor it explicitly,
            // otherwise Compose centers a short, coerced layout and leaves large blank bands.
            val contentHeight = (topPx + groupHeight + bottomPx).coerceAtLeast(viewportPx)
            layout(constraints.maxWidth, constraints.constrainHeight(contentHeight)) {
                metadataPlaceable.placeRelative(inset, topPx)
                bodyPlaceable.placeRelative(0, topPx + bodyTop)
            }
        }
        cover(Modifier.offset {
            IntOffset(
                visibleCover.first.roundToPx(),
                top.roundToPx() - (scrollState.value * compactFraction).roundToInt(),
            )
        }.width(visibleCover.second).scrollable(scrollState, Orientation.Vertical, enabled = !twoColumns))
    }
}
