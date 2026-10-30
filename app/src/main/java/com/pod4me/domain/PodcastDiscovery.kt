package com.pod4me.domain

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class PodcastRecommendation(val title: String, val publisher: String, val feedUrl: String,
    val appleUrl: String, val reason: String, val score: Int = 0)
data class DiscoveryQuery(val term: String, val source: String)

/** Small on-device heuristic; directory search supplies candidates, not a claimed similarity API. */
object PodcastDiscovery {
    private val stopWords = ("the and for with from this that your episode episodes podcast podcasts " +
        "les des une dans pour avec sur par qui que aux est sont plus vous nous notre leur cette tout tous " +
        "part partie saison season radio replay direct interview special speciale suite aujourd hui").split(' ').toSet()

    fun words(text: String): List<String> = Normalizer.normalize(text.take(2048), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+"))
        .filter { it.length >= 4 && it !in stopWords }

    fun queries(library: Library, random: Random = Random.Default): List<DiscoveryQuery> = library.subscriptions
        .shuffled(random).take(3).map { feed ->
            val titleTerms = words(feed.name)
            val episodeTerms = library.episodes.asSequence().filter { it.feedId == feed.id }
                .sortedByDescending { it.published }.take(12).flatMap { words(it.title).distinct().asSequence() }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            // Repeated episode topics also work when the user has given a feed an uninformative name.
            val term = episodeTerms.firstOrNull { it.value >= 2 }?.key ?: titleTerms.firstOrNull()
                ?: episodeTerms.firstOrNull()?.key ?: feed.name.take(100)
            DiscoveryQuery(term, feed.name)
        }.filter { it.term.isNotBlank() }.distinctBy { it.term }

    fun feedKey(url: String): String {
        val uri = URI(FeedParser.httpUrl(url))
        val port = uri.port.takeUnless { it == -1 || it == 80 || it == 443 }?.let { ":$it" }.orEmpty()
        return uri.host.lowercase(Locale.ROOT) + port + uri.rawPath.orEmpty().trimEnd('/') +
            uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    fun select(related: List<PodcastRecommendation>, popular: List<PodcastRecommendation>, subscribed: List<Feed>,
        recent: Set<String>, random: Random = Random.Default): List<PodcastRecommendation> {
        val excluded = subscribed.mapNotNull { runCatching { feedKey(it.url) }.getOrNull() }.toSet()
        fun ranked(rows: List<PodcastRecommendation>) = rows.shuffled(random)
            .filter { feedKey(it.feedUrl) !in excluded }
            .sortedByDescending { it.score - if (feedKey(it.feedUrl) in recent) 1000 else 0 }
        val similar = ranked(related)
        val charts = ranked(popular)
        val result = linkedMapOf<String, PodcastRecommendation>()
        fun add(rows: List<PodcastRecommendation>, limit: Int) {
            for (row in rows) {
                if (result.size >= limit) break
                result.putIfAbsent(feedKey(row.feedUrl), row)
            }
        }
        add(similar, 7)
        add(charts, minOf(10, result.size + 3))
        add(similar + charts, 10)
        return result.values.toList()
    }
}
