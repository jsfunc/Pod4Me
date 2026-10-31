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

    @Test fun previousBatchIsExcludedEvenWithFewCandidatesAndCanReturnLater() {
        val a = podcast(1, 10000)
        val b = podcast(2)
        val previous = setOf(PodcastDiscovery.feedKey(a.feedUrl))
        val next = PodcastDiscovery.select(listOf(a), listOf(a.copy(feedUrl = "http://EXAMPLE.com/1/"), b),
            emptyList(), previous, previous = previous)
        assertEquals(listOf(b.feedUrl), next.map { it.feedUrl })
        val returned = PodcastDiscovery.select(listOf(a, b), emptyList(), emptyList(),
            setOf(PodcastDiscovery.feedKey(a.feedUrl), PodcastDiscovery.feedKey(b.feedUrl)),
            previous = setOf(PodcastDiscovery.feedKey(b.feedUrl)))
        assertEquals(listOf(a.feedUrl), returned.map { it.feedUrl })
        assertTrue(PodcastDiscovery.select(listOf(a), listOf(a), emptyList(), emptySet(), previous = previous).isEmpty())
    }

    @Test fun votesGuideUnseenTopicsAndPublishersWithoutBanningDislikedShows() {
        val feedback = mapOf(
            "liked" to PodcastFeedback(1, "Astronomy", "Science publisher", listOf("Science")),
            "disliked" to PodcastFeedback(-1, "Football", "Sports publisher", listOf("Sports")),
        )
        val science = podcast(1).copy(title = "Space stories", publisher = "Other", topics = listOf("Science"))
        val sports = podcast(2).copy(title = "Football news", publisher = "Other", topics = listOf("Sports"))
        val publisher = podcast(3).copy(title = "Unrelated title", publisher = "SCIENCE PUBLISHER")
        val result = PodcastDiscovery.select(listOf(sports, science, publisher), emptyList(), emptyList(), emptySet(),
            Random(1), feedback = feedback)
        assertEquals(sports.feedUrl, result.last().feedUrl)
        assertTrue(result.first().reason.startsWith("Matches your thumbs-up"))
        val direct = mapOf(PodcastDiscovery.feedKey(science.feedUrl) to PodcastFeedback(-1, science.title, science.publisher, science.topics))
        val reordered = PodcastDiscovery.select(listOf(science, sports), emptyList(), emptyList(), emptySet(), feedback = direct)
        assertEquals(listOf(sports.feedUrl, science.feedUrl), reordered.map { it.feedUrl })
    }

    @Test fun thumbsUpAddsSearchTopicsWithoutSubscriptionsAndClearingRemovesThem() {
        val rating = PodcastFeedback(1, "Astronomy", "Author", listOf("Science"))
        val queries = PodcastDiscovery.queries(Library(), Random(1), mapOf("liked" to rating))
        assertTrue(queries.isNotEmpty())
        assertTrue(queries.all { it.term in setOf("astronomy", "science") })
        assertTrue(PodcastDiscovery.queries(Library(), feedback = mapOf("disliked" to rating.copy(vote = -1))).isEmpty())
        assertTrue(PodcastDiscovery.queries(Library(), feedback = emptyMap()).isEmpty())
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
        valid.put("genres", org.json.JSONArray(listOf("Science", "Podcasts", "Science")))
        assertEquals(listOf("Science"), PodcastDiscoveryClient.parsePodcast(valid, "Related")!!.topics)
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).put("feedUrl", "file:///etc/passwd"), "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).put("collectionViewUrl", "https://example.com/phishing"), "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).put("kind", "podcast-episode"), "Related"))
        assertNull(PodcastDiscoveryClient.parsePodcast(JSONObject(valid.toString()).removeFeed(), "Related"))
    }

    private fun JSONObject.removeFeed() = apply { remove("feedUrl") }
}
