package com.pod4me.domain

import android.app.Application
import android.content.Context
import com.pod4me.data.DiscoveryPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiscoveryPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun podcast(id: Int) = PodcastRecommendation("Astronomy $id", "Publisher $id", "https://example.com/$id",
        "https://podcasts.apple.com/podcast/id$id", "Related", topics = listOf("Science"))

    @Before fun reset() {
        context.getSharedPreferences("podcast_discovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun ratingsAndPreviousBatchSurviveNewStoreAndPreventConsecutiveRepeats() = runBlocking {
        val prefs = DiscoveryPreferences(context)
        val pool = (1..20).map(::podcast)
        val first = PodcastDiscovery.select(pool, emptyList(), emptyList(), emptySet())
        prefs.rate(first.first(), 1)
        prefs.rememberShown(first)
        val reopened = DiscoveryPreferences(context).read()
        val key = PodcastDiscovery.feedKey(first.first().feedUrl)
        assertEquals(PodcastFeedback(1, first.first().title, first.first().publisher, listOf("Science")), reopened.feedback[key])
        assertEquals(first.map { PodcastDiscovery.feedKey(it.feedUrl) }.toSet(), reopened.lastShown)
        val next = PodcastDiscovery.select(pool, emptyList(), emptyList(), reopened.recent.toSet(),
            previous = reopened.lastShown, feedback = reopened.feedback)
        assertEquals(10, next.size)
        assertTrue(next.none { PodcastDiscovery.feedKey(it.feedUrl) in reopened.lastShown })
    }

    @Test fun votesAreMutuallyExclusiveToggleOffAndUseNormalizedFeedIdentity() = runBlocking {
        val prefs = DiscoveryPreferences(context)
        val item = podcast(1)
        val alias = item.copy(feedUrl = "http://EXAMPLE.com/1/")
        val key = PodcastDiscovery.feedKey(item.feedUrl)
        prefs.rate(item, 1)
        prefs.rate(alias, -1)
        assertEquals(1, prefs.state.value.feedback.size)
        assertEquals(-1, DiscoveryPreferences(context).read().feedback[key]!!.vote)
        prefs.rate(item, -1)
        assertTrue(DiscoveryPreferences(context).read().feedback.isEmpty())
    }

    @Test fun emptyResultDoesNotErasePreviousBatchAndHistoryIsBoundedWithoutDroppingRatings() = runBlocking {
        val prefs = DiscoveryPreferences(context)
        prefs.rate(podcast(1), -1)
        for (batch in 0..10) prefs.rememberShown((1..10).map { podcast(batch * 10 + it) })
        val before = prefs.read()
        prefs.rememberShown(emptyList())
        assertEquals(before, DiscoveryPreferences(context).read())
        assertEquals(100, before.recent.size)
        assertEquals(10, before.lastShown.size)
        assertFalse(PodcastDiscovery.feedKey(podcast(1).feedUrl) in before.recent)
        assertEquals(-1, before.feedback[PodcastDiscovery.feedKey(podcast(1).feedUrl)]!!.vote)
    }

    @Test fun concurrentRatingAndBatchWritesPreserveBothChanges() = runBlocking {
        val prefs = DiscoveryPreferences(context)
        listOf(async { prefs.rate(podcast(1), 1) }, async { prefs.rememberShown(listOf(podcast(2))) }).awaitAll()
        val reopened = DiscoveryPreferences(context).read()
        assertEquals(1, reopened.feedback.size)
        assertEquals(setOf(PodcastDiscovery.feedKey(podcast(2).feedUrl)), reopened.lastShown)
    }

    @Test fun immediateSuggestionReadWaitsForAnAlreadyStartedVote() = runBlocking {
        val prefs = DiscoveryPreferences(context)
        val vote = async(start = CoroutineStart.UNDISPATCHED) { prefs.rate(podcast(1), 1) }
        assertEquals(1, prefs.read().feedback[PodcastDiscovery.feedKey(podcast(1).feedUrl)]!!.vote)
        vote.await()
        Unit
    }
}
