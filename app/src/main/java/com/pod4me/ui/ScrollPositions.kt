package com.pod4me.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.listSaver

/** Lives above navigation: keep live states between visits and save only small position records. */
internal class ScrollPositions {
    private data class Position(val item: String, val index: Int, val offset: Int)
    private data class ListEntry(val state: LazyListState, var ids: List<String>)
    private val lists = mutableMapOf<String, ListEntry>()
    private val restoredLists = mutableMapOf<String, Position>()
    private val scrolls = mutableMapOf<String, ScrollState>()
    private val restoredScrolls = mutableMapOf<String, Int>()

    // Call only once content is ready, so loading/empty placeholders cannot consume a saved position.
    fun list(key: String, ids: List<String>): LazyListState {
        require(ids.isNotEmpty())
        val entry = lists.getOrPut(key) {
            val saved = restoredLists.remove(key)
            val index = saved?.let { position ->
                ids.indexOf(position.item).takeIf { it >= 0 } ?: position.index.coerceIn(ids.indices)
            } ?: 0
            ListEntry(LazyListState(index, saved?.offset ?: 0), ids)
        }
        entry.ids = ids
        return entry.state
    }

    fun scroll(key: String): ScrollState = scrolls.getOrPut(key) {
        ScrollState(restoredScrolls.remove(key) ?: 0)
    }

    internal fun save(): List<Any> = buildList {
        val positions = restoredLists.toMutableMap()
        lists.forEach { (key, entry) ->
            val index = entry.state.firstVisibleItemIndex
            val item = entry.state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
                ?: entry.ids.getOrNull(index).orEmpty()
            positions[key] = Position(item, index, entry.state.firstVisibleItemScrollOffset)
        }
        positions.forEach { (key, position) ->
            add("list"); add(key); add(position.item); add(position.index); add(position.offset)
        }
        val offsets = restoredScrolls + scrolls.mapValues { it.value.value }
        offsets.forEach { (key, offset) -> add("scroll"); add(key); add(""); add(0); add(offset) }
    }

    companion object {
        internal fun restore(values: List<Any>): ScrollPositions = ScrollPositions().apply {
            values.chunked(5).forEach { row ->
                val key = row[1] as String
                val offset = (row[4] as Int).coerceAtLeast(0)
                if (row[0] == "list") restoredLists[key] = Position(row[2] as String, (row[3] as Int).coerceAtLeast(0), offset)
                else restoredScrolls[key] = offset
            }
        }
        val Saver = listSaver<ScrollPositions, Any>(save = { it.save() }, restore = { restore(it) })
    }
}
