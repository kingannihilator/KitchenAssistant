package com.pancakeworks.fridgegrub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color

/**
 * A thin rounded scrollbar thumb drawn over a [LazyColumn].
 * Sizes and positions the thumb based on the visible item range reported by [state].
 */
@Composable
fun LazyListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val info = state.layoutInfo
        val visibleItems = info.visibleItemsInfo
        if (visibleItems.isEmpty() || info.totalItemsCount == 0) return@Canvas

        val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
        val totalContentHeight = avgItemSize * info.totalItemsCount
        if (totalContentHeight <= size.height) return@Canvas

        val thumbHeight = ((size.height / totalContentHeight) * size.height).coerceAtLeast(40f)
        val firstItem = visibleItems.first()
        val scrollOffset = firstItem.index * avgItemSize - firstItem.offset
        val scrollFraction = scrollOffset / (totalContentHeight - size.height)
        val thumbTop = (scrollFraction * (size.height - thumbHeight)).coerceIn(0f, size.height - thumbHeight)

        drawRoundRect(
            color = Color(0x889E9E9E),
            topLeft = Offset(0f, thumbTop),
            size = GeomSize(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2)
        )
    }
}
