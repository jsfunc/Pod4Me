package com.pod4me.ui

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScrollPositionsTest {
    @Test fun switchingViewsKeepsIndependentLivePositions() {
        val positions = ScrollPositions()
        val ids = listOf("a", "b", "c", "d")
        val first = positions.list("library:unheard:PUBLICATION", ids)
        first.requestScrollToItem(2, 19)
        val other = positions.list("library:starred:STARS", ids)
        other.requestScrollToItem(1, 7)
        assertSame(first, positions.list("library:unheard:PUBLICATION", ids))
        assertEquals(2, first.firstVisibleItemIndex)
        assertEquals(19, first.firstVisibleItemScrollOffset)
        assertEquals(1, other.firstVisibleItemIndex)
        assertEquals(7, other.firstVisibleItemScrollOffset)
    }

    @Test fun recreationFindsSameEpisodeAfterNewEpisodesAreInserted() {
        val original = ScrollPositions()
        original.list("latest:feed", listOf("a", "b", "c")).requestScrollToItem(1, 23)
        val restored = ScrollPositions.restore(original.save())
        val state = restored.list("latest:feed", listOf("new", "a", "b", "c"))
        assertEquals(2, state.firstVisibleItemIndex)
        assertEquals(23, state.firstVisibleItemScrollOffset)
    }

    @Test fun deletedAnchorFallsBackToNearestSurvivingPosition() {
        val original = ScrollPositions()
        original.list("queue", listOf("a", "b", "c")).requestScrollToItem(2, 11)
        val restored = ScrollPositions.restore(original.save())
        val state = restored.list("queue", listOf("a"))
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(11, state.firstVisibleItemScrollOffset)
    }

    @Test fun unvisitedViewsSurviveAnotherSavedStateRoundTrip() {
        val original = ScrollPositions()
        original.list("latest:feed-a", listOf("a", "b")).requestScrollToItem(1, 17)
        original.list("search:feed-a:space", listOf("c", "d")).requestScrollToItem(1, 29)
        val restored = ScrollPositions.restore(original.save())
        restored.list("feeds", listOf("feed-a"))
        val again = ScrollPositions.restore(restored.save())
        assertEquals(17, again.list("latest:feed-a", listOf("a", "b")).firstVisibleItemScrollOffset)
        assertEquals(29, again.list("search:feed-a:space", listOf("c", "d")).firstVisibleItemScrollOffset)
    }

    @Test fun notesAndPlayerScrollIndependentlyAndRestore() {
        val original = ScrollPositions()
        val notes = original.scroll("info:a")
        notes.dispatchRawDelta(427f)
        original.scroll("player:a").dispatchRawDelta(120f)
        original.scroll("info:b").dispatchRawDelta(36f)
        assertSame(notes, original.scroll("info:a"))
        val restored = ScrollPositions.restore(original.save())
        assertEquals(427, restored.scroll("info:a").value)
        assertEquals(120, restored.scroll("player:a").value)
        assertEquals(36, restored.scroll("info:b").value)
    }
}
