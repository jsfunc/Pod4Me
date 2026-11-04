package com.pod4me.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import com.pod4me.domain.RadioCatalogue
import com.pod4me.domain.RadioStation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RadioDragStateTest {
    private val stations = RadioCatalogue.defaults
    private val list = LazyListState()
    private var visible = stations
    private var scroll = 0
    private var heights = stations.associate { it.id to 100 }
    private val drag = RadioDragState(list) {
        object : LazyListLayoutInfo by list.layoutInfo {
            override val viewportStartOffset = 0
            override val viewportEndOffset = 300
            override val visibleItemsInfo = visible.mapIndexed { i, station ->
                object : LazyListItemInfo {
                    override val index = i
                    override val key = station.id
                    override val offset = visible.take(i).sumOf { heights.getValue(it.id) } - scroll
                    override val size = heights.getValue(station.id)
                    override val contentType: Any? = null
                }
            }
        }
    }

    @Test fun crossingMovesPreviewAndCancellationDiscardsIt() {
        drag.start(40f, stations)
        drag.move(120f)
        assertEquals(listOf(stations[1], stations[0], stations[2]), drag.preview)
        assertEquals(120f, drag.translation(), .01f)
        visible = drag.preview!! // Next layout catches up; row stays under the finger.
        assertEquals(20f, drag.translation(), .01f)
        drag.reset()
        assertNull(drag.preview)
        assertNull(drag.id)
        assertNull(drag.finish())
    }

    @Test fun movingBothDirectionsAndDroppingProducesOneDurableOrder() {
        drag.start(250f, stations)
        drag.move(-210f)
        assertEquals(listOf(stations[2], stations[0], stations[1]), drag.preview)
        visible = drag.preview!!
        drag.move(120f)
        val expected = listOf(stations[0], stations[2], stations[1])
        assertEquals(expected, drag.preview)
        assertEquals(expected.map(RadioStation::id), drag.finish())
        assertNull(drag.finish())
        assertTrue(drag.saving)
        drag.start(40f, stations)
        assertNull(drag.id)
        drag.reset()
        assertFalse(drag.saving)
    }

    @Test fun edgeScrollingKeepsDragInViewportAndTracksChangedLayout() {
        drag.start(150f, stations)
        assertEquals(0f, drag.edgeFraction(60f), .01f)
        drag.move(500f)
        assertTrue(drag.edgeFraction(60f) > 0)
        scroll = 50
        assertEquals(200f, drag.translation(), .01f)
        drag.move(-500f)
        assertTrue(drag.edgeFraction(60f) < 0)
    }

    @Test fun unequalRowsDoNotBounceBackAndCanReachTheLastVisibleRow() {
        heights = mapOf(stations[0].id to 50, stations[1].id to 150, stations[2].id to 100)
        drag.start(25f, stations)
        drag.move(120f) // Past the taller neighbour's center.
        assertEquals(listOf(stations[1], stations[0], stations[2]), drag.preview)
        visible = drag.preview!!
        drag.move(0f) // Next frame must not undo the move.
        assertEquals(visible, drag.preview)
        drag.move(500f) // Finger beyond the lower edge still crosses the final row.
        assertEquals(listOf(stations[1], stations[2], stations[0]), drag.preview)
        visible = drag.preview!!
        drag.move(0f)
        assertEquals(visible, drag.preview)
    }
}
