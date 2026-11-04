package com.pod4me.domain

import org.junit.Assert.*
import org.junit.Test

class YouTubeChannelsTest {
    @Test fun channelAliasesAndTabsNormalizeWithoutVideoOrTrackingParameters() {
        assertEquals(YouTubeChannels.WAATH_URL, YouTubeChannels.normalize("http://m.youtube.com/@WeAreAthletic_WAATH/videos?si=tracking"))
        assertEquals("https://www.youtube.com/channel/UCAbCd", YouTubeChannels.normalize("https://youtube.com/channel/UCAbCd/featured"))
        assertFalse(YouTubeChannels.isYouTube("https://youtube.com.evil.example/@channel"))
        for (url in listOf("https://youtube.com/watch?v=abc", "https://youtu.be/abc", "https://youtube.com/playlist?list=abc", "https://youtube.com/@abc/watch/extra", "https://example.com/@abc")) {
            try { YouTubeChannels.normalize(url); fail("Accepted $url") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun importKeepsNamesAndLeavesRssFormatCompatible() {
        val text = "https://example.com/rss [Podcast]\n${YouTubeChannels.WAATH_URL} [Guillaume Guillou — WE ARE ATHLETIC]\n"
        assertEquals(2, FeedListFormat.urls(text).size)
        assertEquals(YouTubeChannels.WAATH_NAME, YouTubeChannels.importedName(text, YouTubeChannels.WAATH_URL))
        assertNull(YouTubeChannels.importedName(YouTubeChannels.WAATH_URL, YouTubeChannels.WAATH_URL))
    }
}
