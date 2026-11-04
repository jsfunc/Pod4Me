package com.pod4me.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pod4me.domain.RadioStation
import kotlin.math.abs

/** Preview moves in memory; persist once on release, discard the preview on cancellation. */
internal class RadioDragState(val list: LazyListState, private val layoutInfo: () -> LazyListLayoutInfo = { list.layoutInfo }) {
    var preview by mutableStateOf<List<RadioStation>?>(null)
        private set
    var id by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    private var center by mutableFloatStateOf(0f)

    fun start(y: Float, stations: List<RadioStation>) {
        if (saving) return
        val item = layoutInfo().visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return
        if (stations.none { it.id == item.key }) return
        preview = stations
        id = item.key as String
        center = item.offset + item.size / 2f
    }

    fun move(delta: Float) {
        if (id == null) return
        val layout = layoutInfo()
        center = (center + delta).coerceIn(layout.viewportStartOffset.toFloat(), layout.viewportEndOffset.toFloat())
        val order = preview ?: return
        val from = order.indexOfFirst { it.id == id }
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        // Wait for the previous move's layout before interpreting another crossing.
        if (dragged.index != from) return
        // Cross the neighbour's center in the direction of travel. With unequal row heights,
        // testing only its bounds can immediately move the row back after the new layout.
        val target = layout.visibleItemsInfo.filter {
            order.getOrNull(it.index)?.id == it.key &&
                ((it.index > from && center > it.offset + it.size / 2f) ||
                    (it.index < from && center < it.offset + it.size / 2f))
        }.minByOrNull { abs(center - it.offset - it.size / 2f) } ?: return
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        preview = order.toMutableList().apply { add(target.index, removeAt(from)) }
    }

    fun translation(): Float {
        val item = layoutInfo().visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
        return center - item.offset - item.size / 2f
    }

    /** Signed edge proximity, for frame-paced scrolling while the finger is held still. */
    fun edgeFraction(edge: Float): Float {
        val layout = layoutInfo()
        val top = layout.viewportStartOffset + layout.beforeContentPadding
        val bottom = layout.viewportEndOffset - layout.afterContentPadding
        return when {
            center < top + edge -> -((top + edge - center) / edge).coerceIn(0f, 1f)
            center > bottom - edge -> ((center - bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    fun finish(): List<String>? {
        if (id == null) return null
        id = null
        saving = true
        return preview?.map { it.id }
    }

    fun reset() { id = null; preview = null; saving = false }
}
