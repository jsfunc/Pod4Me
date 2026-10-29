package com.pod4me.domain

import org.junit.Assert.*
import org.junit.Test

class SuggestionsTest {
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
        assertEquals(Suggestion(feed.url, "feed"), Suggestions.parse(Suggestions.feed(feed)))
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
