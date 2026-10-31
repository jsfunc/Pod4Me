package com.pod4me.domain

import com.pod4me.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class SuggestionsTest {
    @Test fun outgoingMessageHasOneHttpsLinkAndNoDuplicateAudioUrl() {
        val episode = Episode("e", "f", "A & B + Café?", "https://example.com/audio.mp3", "", 0)
        val message = Suggestions.episode(episode, null)
        val lines = message.lines()
        assertEquals(2, lines.size)
        assertEquals("Episode suggestion: ${episode.title}", lines[0])
        assertTrue(lines[1].startsWith(BuildConfig.SHARE_BASE_URL + "#episode?"))
        assertFalse(message.contains("pod4me://"))
        assertFalse(message.contains(episode.audioUrl))
    }
    @Test fun websiteLinkTakesPriorityOverAnUnrelatedUrlInPersonalNote() {
        val feed = Feed("f", "https://example.com/feed", "Café + music", 30)
        val message = "See https://example.org/about too\n" + Suggestions.feed(feed)
        assertEquals(Suggestion(feed.url, "feed", feed.name), Suggestions.parse(message))
    }
    @Test fun foreignWebsiteDoesNotInterpretItsFragmentAsPod4MeMetadata() {
        val result = Suggestions.parse("https://example.org/#episode?url=https%3A%2F%2Fexample.com%2Fa.mp3")
        assertEquals("unknown", result.kind)
        assertNotEquals("https://example.com/a.mp3", result.url)
    }
    @Test(expected = IllegalArgumentException::class) fun websiteWithoutRecommendationIsRejected() {
        Suggestions.parse(BuildConfig.SHARE_BASE_URL)
    }
    @Test(expected = IllegalArgumentException::class) fun websiteRejectsUnsafeAudioUrl() {
        Suggestions.parse(BuildConfig.SHARE_BASE_URL + "#episode?url=javascript%3Aalert%281%29")
    }
    @Test(expected = IllegalArgumentException::class) fun websiteRejectsUnsafeSourceFeed() {
        Suggestions.parse(BuildConfig.SHARE_BASE_URL + "#episode?url=https%3A%2F%2Fexample.com%2Fa.mp3&feed=file%3A%2F%2F%2Fetc%2Fpasswd")
    }
    @Test(expected = IllegalArgumentException::class) fun websiteRejectsDuplicatePayloadFields() {
        Suggestions.parse(BuildConfig.SHARE_BASE_URL + "#feed?url=https%3A%2F%2Fexample.com%2Fa&url=https%3A%2F%2Fexample.com%2Fb")
    }
    @Test(expected = IllegalArgumentException::class) fun websiteRejectsUnknownRecommendationType() {
        Suggestions.parse(BuildConfig.SHARE_BASE_URL + "#other?url=https%3A%2F%2Fexample.com%2Ffeed")
    }
    @Test fun browserFallbackCustomLinkStillKeepsEpisodeMetadata() {
        val result = Suggestions.parse("pod4me://episode?url=https%3A%2F%2Fexample.com%2Fa.mp3&title=Caf%C3%A9%20%2B%20music&feed=https%3A%2F%2Fexample.com%2Ffeed")
        assertEquals(Suggestion("https://example.com/a.mp3", "episode", "Café + music", "https://example.com/feed"), result)
    }
    @Test fun episodeSuggestionRoundTripsReservedCharacters() {
        val feed = Feed("f", "https://example.com/feed?x=1&lang=fr", "Café", 30)
        val ep = Episode("e", "f", "A & B + Café?", "https://cdn.example.com/audio?id=a%2Fb&x=1", "", 0)
        val result = Suggestions.parse(Suggestions.episode(ep, feed))
        assertEquals(ep.audioUrl, result.url)
        assertEquals(ep.title, result.title)
        assertEquals(feed.url, result.feedUrl)
        assertEquals("episode", result.kind)
    }
    @Test fun feedSuggestionRoundTrips() {
        val feed = Feed("f", "https://example.com/feed", "Feed", null)
        assertEquals(Suggestion(feed.url, "feed", feed.name), Suggestions.parse(Suggestions.feed(feed)))
    }
    @Test fun copiedWholeMessageWithPersonalNoteKeepsTheEpisode() {
        val ep = Episode("e", "f", "Café & music", "https://example.com/audio?id=1&token=a%2Fb", "", 0)
        val message = "You might enjoy this!\n" + Suggestions.episode(ep, null) + "\nLet me know what you think."
        assertEquals(Suggestion(ep.audioUrl, "episode", ep.title), Suggestions.parse(message))
    }
    @Test fun previousVersionFeedLinksStillWorkWithoutATitle() {
        assertEquals(Suggestion("https://example.com/feed", "feed"),
            Suggestions.parse("pod4me://feed?url=https%3A%2F%2Fexample.com%2Ffeed"))
    }
    @Test fun plainAudioUrlIsRecognized() {
        assertEquals("episode", Suggestions.parse("Listen: https://example.com/episode.MP3?download=1").kind)
    }
    @Test fun unrecognizedWebUrlRequiresChoice() {
        assertEquals("unknown", Suggestions.parse("https://example.com/podcast").kind)
    }
    @Test(expected = IllegalArgumentException::class) fun unsafeSuggestionIsRejected() {
        Suggestions.parse("pod4me://episode?url=file%3A%2F%2F%2Fetc%2Fpasswd")
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedMessageIsRejected() {
        Suggestions.parse("x".repeat(64_001))
    }
    @Test fun stableSortAndProgressOrder() {
        val first = Episode("a", "f", "A", "https://example.com/a", "", 1, duration = 100, position = 50)
        val second = first.copy(id = "b", position = 0, stars = 5)
        val library = Library(episodes = listOf(first, second))
        assertEquals(listOf("b", "a"), library.episodes.sorted(TrackSort.PROGRESS, library).map { it.id })
        assertEquals(listOf("b", "a"), library.episodes.sorted(TrackSort.STARS, library).map { it.id })
    }
}
