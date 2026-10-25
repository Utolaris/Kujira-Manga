package com.par9uet.jm.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.structuralEqualityPolicy

/** Retained pager pages keep their state, but only pages intersecting the viewport load covers. */
internal val LocalCoverWorkActive = compositionLocalOf { true }
internal val LocalCoverInViewport = compositionLocalOf { true }

/** Grid precomposition/premeasurement must not start offscreen image requests. */
@Composable
internal fun rememberVisibleCoverKeys(state: LazyGridState): State<Set<Any>> = remember(state) {
    derivedStateOf(structuralEqualityPolicy()) { state.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key } }
}

/** Read visibility inside the item: a new row must not invalidate the grid's content DSL. */
@Composable
internal fun CoverViewportItem(
    visibleKeys: State<Set<Any>>,
    itemKey: Any,
    content: @Composable () -> Unit,
) {
    val visible by remember(visibleKeys, itemKey) { coverItemVisibility(visibleKeys, itemKey) }
    CompositionLocalProvider(LocalCoverInViewport provides visible, content = content)
}

internal fun coverItemVisibility(visibleKeys: State<Set<Any>>, itemKey: Any): State<Boolean> =
    derivedStateOf(structuralEqualityPolicy()) { itemKey in visibleKeys.value }

internal fun isCoverPageVisible(page: Int, currentPage: Int, offsetFraction: Float): Boolean =
    kotlin.math.abs(currentPage - page + offsetFraction) < 1f
