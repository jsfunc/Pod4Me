package com.pod4me.domain

import com.pod4me.BuildConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class Suggestion(val url: String, val kind: String, val title: String = "", val feedUrl: String? = null)

object Suggestions {
    private val website = URI(BuildConfig.SHARE_BASE_URL)
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String) = try { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Suggestion contains invalid URL encoding") }
    fun feed(feed: Feed) = "Podcast suggestion: ${feed.name}\n${BuildConfig.SHARE_BASE_URL}#feed?url=${encode(feed.url)}&title=${encode(feed.name)}"
    fun episode(episode: Episode, feed: Feed?) = buildString {
        append("Episode suggestion: ${episode.title}\n")
        append("${BuildConfig.SHARE_BASE_URL}#episode?url=${encode(episode.audioUrl)}&title=${encode(episode.title)}")
        if (!feed?.url.isNullOrBlank()) append("&feed=${encode(requireNotNull(feed).url)}")
    }
    fun parse(text: String): Suggestion {
        require(text.length <= 64_000) { "This shared message is too long" }
        val webLinks = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }
        for (link in webLinks) {
            val uri = runCatching { URI(link) }.getOrNull() ?: continue
            if (uri.host.equals(website.host, ignoreCase = true) && uri.rawPath == website.rawPath &&
                uri.userInfo == null && (uri.port == -1 || uri.port == if (uri.scheme.equals("https", true)) 443 else 80)) {
                val payload = requireNotNull(uri.rawFragment) { "This link has no podcast or episode. Ask the sender to share it from Pod4Me." }
                return parseDeep("pod4me://$payload")
            }
        }
        val deep = Regex("pod4me://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value
        if (deep != null) return parseDeep(deep)
        val url = webLinks.firstOrNull()?.trimEnd('.', ',', ')', ']', '"')
            ?: throw IllegalArgumentException("Share an RSS feed URL or a direct audio URL into Pod4Me")
        val normalized = FeedParser.httpUrl(url)
        val path = URI.create(normalized).path.orEmpty().lowercase()
        val kind = if (listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac").any(path::endsWith)) "episode" else "unknown"
        return Suggestion(normalized, kind)
    }

    private fun parseDeep(deep: String): Suggestion {
        val uri = try { URI.create(deep) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("This Pod4Me suggestion link is malformed") }
        require(uri.userInfo == null && uri.port == -1 && uri.path.isNullOrEmpty() && uri.rawFragment == null) { "This Pod4Me suggestion link is malformed" }
        val fields = (uri.rawQuery ?: "").split('&').filter { '=' in it }.map {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        require(fields.map { it.first }.distinct().size == fields.size) { "Suggestion contains duplicate fields" }
        val query = fields.toMap()
        val kind = requireNotNull(uri.host) { "Suggestion is missing its feed or episode type" }.lowercase()
        require(kind == "feed" || kind == "episode") { "Unknown Pod4Me suggestion type" }
        return Suggestion(FeedParser.httpUrl(requireNotNull(query["url"]) { "Suggestion is missing a URL" }), kind,
            query["title"].orEmpty(), query["feed"]?.let(FeedParser::httpUrl))
    }
}
