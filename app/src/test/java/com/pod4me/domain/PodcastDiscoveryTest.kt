package com.pod4me.domain

import android.app.Application
import com.pod4me.data.PodcastDiscoveryClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PodcastDiscoveryTest {
    private fun podcast(id: Int, score: Int = 0) = PodcastRecommendation("Show $id", "Publisher", "https://example.com/$id",
        "https://podcasts.apple.com/fr/podcast/id$id", "Related", score)

    @Test fun selectsTenWithRelatedAndPopularMixWithoutSubscriptionsOrDuplicates() {
        val related = (1..15).map { podcast(it, 100 - it) } + podcast(1, 200).copy(feedUrl = "http://EXAMPLE.com/1/")
        val popular = (16..30).map { podcast(it, 100 - it).copy(reason = "Popular") }
        val result = PodcastDiscovery.select(related, popular, listOf(Feed("f", "https://example.com/2", "Saved", 30)), emptySet(), Random(1))
        assertEquals(10, result.size)
        assertEquals(10, result.map { PodcastDiscovery.feedKey(it.feedUrl) }.toSet().size)
        assertFalse(result.any { it.feedUrl == "https://example.com/2" })
        assertEquals(3, result.count { it.reason == "Popular" })
    }

    @Test fun recentSuggestionsGiveWayToFreshCandidates() {
        val candidates = (1..30).map { podcast(it, 100 - it) }
        val first = PodcastDiscovery.select(candidates, emptyList(), emptyList(), emptySet(), Random(1))
        val recent = first.map { PodcastDiscovery.feedKey(it.feedUrl) }.toSet()
        val next = PodcastDiscovery.select(candidates, emptyList(), emptyList(), recent, Random(2))
        assertEquals(10, next.size)
        assertTrue(next.none { PodcastDiscovery.feedKey(it.feedUrl) in recent })
    }

    @Test fun emptyLibraryUsesChartsAndLimitedResultsAreNotInvented() {
        assertTrue(PodcastDiscovery.queries(Library()).isEmpty())
        val result = PodcastDiscovery.select(emptyList(), listOf(podcast(1), podcast(2)), emptyList(), emptySet())
        assertEquals(2, result.size)
    }

    @Test fun queriesUseSubscribedFeedsAndRepeatedEpisodeTopicsWithoutSendingNotes() {
        val feeds = listOf(Feed("yes", "https://example.com/feed", "My daily show", 30),
            Feed("no", "https://example.com/hidden", "Unsubscribed", 30, subscribed = false))
        val episodes = (1..3).map { Episode("$it", "yes", "Astronomie $it", "https://example.com/$it.mp3", "private notes", it.toLong()) }
        val queries = PodcastDiscovery.queries(Library(feeds = feeds, episodes = episodes), Random(1))
        assertEquals(listOf(DiscoveryQuery("astronomie", "My daily show")), queries)
        assertEquals(listOf("economie"), PodcastDiscovery.words("Les épisodes : Économie"))
    }

    @Test fun directoryParserAcceptsOnlyPodcastsWithSafeFeedsAndAppleLinks() {
        val valid = JSONObject().put("kind", "podcast").put("feedUrl", "https://example.com/feed")
            .put("collectionViewUrl", "https://podcasts.apple.com/fr/podcast/id123").put("collectionName", "A podcast").put("artistName", "Author")
        assertNotNull(PodcastDiscoveryClient.parsePodcast(valid, "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).put("feedUrl", "file:///etc/passwd"), "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).put("collectionViewUrl", "https://example.com/phishing"), "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).put("kind", "podcast-episode"), "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).removeFeed(), "Related"))
    }

    private fun JSONObject.removeFeed() = apply { remove("feedUrl") }
}
