package com.pod4me.ui

import android.app.Application
import com.pod4me.domain.Episode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScrollPositionsTest {
    private fun episode(id: String, date: Long) = Episode(id, "feed", id, "https://example.com/$id", "", date)

    @Test fun latestStartsAtMostRecentPublishedEpisodeWithoutDroppingFutureEntries() {
        val episodes = listOf(episode("future", 3000), episode("later", 2000), episode("now", 1000), episode("past", 900))
        assertEquals(2, latestInitialIndex(episodes, 1000))
        assertEquals(3, latestInitialIndex(episodes, 999))
        assertEquals(4, episodes.size)
        assertEquals(0, latestInitialIndex(episodes, 500)) // All future: keep the list usable.
        assertEquals(0, latestInitialIndex(episodes.drop(3), 1000))
        assertEquals(0, latestInitialIndex(emptyList(), 1000))
    }

    @Test fun latestDefaultOnlyAppliesToNewViewsAndRestoredPositionsWin() {
        val ids = listOf("future", "now", "past", "older")
        val original = ScrollPositions()
        val state = original.list("latest:all", ids, initialIndex = 1, centerInitially = true)
        assertEquals(1, state.firstVisibleItemIndex)
        state.requestScrollToItem(3, 21)
        assertSame(state, original.list("latest:all", ids, initialIndex = 2, centerInitially = true))
        assertEquals(3, state.firstVisibleItemIndex)
        val restored = ScrollPositions.restore(original.save())
        val again = restored.list("latest:all", listOf("new") + ids, initialIndex = 2, centerInitially = true)
        assertEquals(4, again.firstVisibleItemIndex)
        assertEquals(21, again.firstVisibleItemScrollOffset)
        assertEquals(0, original.list("search:feed:query", ids).firstVisibleItemIndex)
        assertEquals(2, original.list("latest:feed", ids, initialIndex = 2).firstVisibleItemIndex)
    }

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
