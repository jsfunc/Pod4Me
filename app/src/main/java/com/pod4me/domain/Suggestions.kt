package com.pod4me.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class Suggestion(val url: String, val kind: String, val title: String = "", val feedUrl: String? = null)

object Suggestions {
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    fun feed(feed: Feed) = "Podcast suggestion: ${feed.name}\n${feed.url}\npod4me://feed?url=${encode(feed.url)}"
    fun episode(episode: Episode, feed: Feed?) = buildString {
        append("Episode suggestion: ${episode.title}\n${episode.audioUrl}\n")
        append("pod4me://episode?url=${encode(episode.audioUrl)}&title=${encode(episode.title)}")
        if (!feed?.url.isNullOrBlank()) append("&feed=${encode(requireNotNull(feed).url)}")
    }
    fun parse(text: String): Suggestion {
        require(text.length <= 64_000) { "This shared message is too long" }
        val deep = Regex("pod4me://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value
        if (deep != null) {
            val uri = URI.create(deep)
            val query = (uri.rawQuery ?: "").split('&').filter { '=' in it }.associate {
                decode(it.substringBefore('=')) to decode(it.substringAfter('='))
            }
            val kind = requireNotNull(uri.host).lowercase()
            require(kind == "feed" || kind == "episode") { "Unknown Pod4Me suggestion type" }
            return Suggestion(FeedParser.httpUrl(requireNotNull(query["url"]) { "Suggestion is missing a URL" }), kind,
                query["title"].orEmpty(), query["feed"]?.let(FeedParser::httpUrl))
        }
        val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']', '"')
            ?: throw IllegalArgumentException("Share an RSS feed URL or a direct audio URL into Pod4Me")
        val normalized = FeedParser.httpUrl(url)
        val path = URI.create(normalized).path.orEmpty().lowercase()
        val kind = if (listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac").any(path::endsWith)) "episode" else "unknown"
        return Suggestion(normalized, kind)
    }
}
