package com.pod4me.domain

import java.net.URI

/** Channel subscriptions open in YouTube; their URLs are never fetched as media or RSS. */
object YouTubeChannels {
    const val WAATH_URL = "https://www.youtube.com/@weareathletic_waath"
    const val WAATH_NAME = "Guillaume Guillou — WE ARE ATHLETIC"

    fun isYouTube(url: String): Boolean = runCatching {
        URI(url.trim()).host?.lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
    }.getOrDefault(false)

    fun normalize(url: String): String {
        val uri = URI(FeedParser.httpUrl(url.trim()))
        require(isYouTube(url) && uri.host != "youtu.be" && uri.userInfo == null && uri.port == -1) {
            "Use a YouTube channel URL, such as https://www.youtube.com/@channel"
        }
        val parts = uri.path.trim('/').split('/')
        val handle = parts.firstOrNull()?.takeIf { it.startsWith('@') && it.length > 1 }
        val count = if (handle != null) 1 else 2
        require(handle != null || (parts.size >= 2 && parts[0] in setOf("channel", "c", "user") && parts[1].isNotBlank())) {
            "Paste the channel URL, not an individual video or playlist"
        }
        require(parts.size == count || (parts.size == count + 1 && parts.last() in setOf("videos", "featured", "streams", "shorts", "podcasts", "playlists", "about"))) {
            "Use the channel's home page URL"
        }
        val path = parts.take(count).joinToString("/").let { if (handle != null) it.lowercase() else it }
        return URI("https", "www.youtube.com", "/$path", null).toASCIIString()
    }

    fun defaultName(url: String): String = if (url == WAATH_URL) WAATH_NAME else URI(url).path.substringAfterLast('/').removePrefix("@")

    fun importedName(text: String, url: String): String? = text.lineSequence().map(String::trim)
        .firstOrNull { FeedListFormat.urls(it).firstOrNull() == url }?.substringAfter(url)?.trim()
        ?.takeIf { it.startsWith('[') && it.endsWith(']') }?.drop(1)?.dropLast(1)?.trim()
        ?.takeIf { it.isNotEmpty() }?.take(FeedParser.MAX_TITLE_CHARS)
}
