package com.pod4me.domain

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class PodcastRecommendation(val title: String, val publisher: String, val feedUrl: String,
    val appleUrl: String, val reason: String, val score: Int = 0, val topics: List<String> = emptyList())
data class DiscoveryQuery(val term: String, val source: String)
data class PodcastFeedback(val vote: Int, val title: String, val publisher: String, val topics: List<String>)
data class DiscoveryMemory(val feedback: Map<String, PodcastFeedback> = emptyMap(),
    val lastShown: Set<String> = emptySet(), val recent: List<String> = emptyList())

/** Small on-device heuristic; directory search supplies candidates, not a claimed similarity API. */
object PodcastDiscovery {
    private val stopWords = ("the and for with from this that your episode episodes podcast podcasts " +
        "les des une dans pour avec sur par qui que aux est sont plus vous nous notre leur cette tout tous " +
        "part partie saison season radio replay direct interview special speciale suite aujourd hui").split(' ').toSet()

    fun words(text: String): List<String> = Normalizer.normalize(text.take(2048), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+"))
        .filter { it.length >= 4 && it !in stopWords }

    fun queries(library: Library, random: Random = Random.Default,
        feedback: Map<String, PodcastFeedback> = emptyMap()): List<DiscoveryQuery> {
        val interests = interestWeights(feedback.values)
        val liked = interests.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            .take(8).shuffled(random).take(2).map { DiscoveryQuery(it.key, "Your thumbs-up choices") }
        val subscribed = library.subscriptions.shuffled(random).take(3).map { feed ->
            val titleTerms = words(feed.name)
            val episodeTerms = library.episodes.asSequence().filter { it.feedId == feed.id }
                .sortedByDescending { it.published }.take(12).flatMap { words(it.title).distinct().asSequence() }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            // Repeated episode topics also work when the user has given a feed an uninformative name.
            val term = episodeTerms.firstOrNull { it.value >= 2 }?.key ?: titleTerms.firstOrNull()
                ?: episodeTerms.firstOrNull()?.key ?: feed.name.take(100)
            DiscoveryQuery(term, feed.name)
        }.filter { it.term.isNotBlank() }.distinctBy { it.term }
            .sortedByDescending { interests[it.term] ?: 0 }
        // Reserve room for exploration through subscriptions; feedback also works with an empty library.
        return (liked.take(1) + subscribed.take(2) + liked.drop(1) + subscribed.drop(2)).distinctBy { it.term }.take(3)
    }

    private fun features(title: String, topics: List<String>) = words(title + " " + topics.joinToString(" ")).toSet()
    private fun interestWeights(feedback: Collection<PodcastFeedback>): Map<String, Int> {
        val weights = mutableMapOf<String, Int>()
        feedback.forEach { rating -> features(rating.title, rating.topics).forEach { term ->
            weights[term] = (weights[term] ?: 0) + rating.vote
        } }
        return weights
    }

    private fun publisherKey(name: String) = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    fun feedKey(url: String): String {
        val uri = URI(FeedParser.httpUrl(url))
        val port = uri.port.takeUnless { it == -1 || it == 80 || it == 443 }?.let { ":$it" }.orEmpty()
        return uri.host.lowercase(Locale.ROOT) + port + uri.rawPath.orEmpty().trimEnd('/') +
            uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    fun select(related: List<PodcastRecommendation>, popular: List<PodcastRecommendation>, subscribed: List<Feed>,
        recent: Set<String>, random: Random = Random.Default, previous: Set<String> = emptySet(),
        feedback: Map<String, PodcastFeedback> = emptyMap()): List<PodcastRecommendation> {
        val excluded = subscribed.mapNotNull { runCatching { feedKey(it.url) }.getOrNull() }.toSet() + previous
        val interests = interestWeights(feedback.values)
        val publishers = feedback.values.filter { it.publisher.isNotBlank() }.groupBy { publisherKey(it.publisher) }
            .mapValues { (_, ratings) -> ratings.sumOf { it.vote }.coerceIn(-3, 3) }
        fun affinity(row: PodcastRecommendation): Int {
            val direct = (feedback[feedKey(row.feedUrl)]?.vote ?: 0) * 100
            val topics = features(row.title, row.topics).sumOf { (interests[it] ?: 0).coerceIn(-3, 3) * 10 }.coerceIn(-60, 60)
            val publisher = (publishers[publisherKey(row.publisher)] ?: 0) * 12
            return direct + topics + publisher
        }
        fun ranked(rows: List<PodcastRecommendation>) = rows.shuffled(random)
            .filter { feedKey(it.feedUrl) !in excluded }
            .map { row ->
                val preference = affinity(row)
                row.copy(score = row.score + preference - if (feedKey(row.feedUrl) in recent) 1000 else 0,
                    reason = if (preference > 0) "Matches your thumbs-up choices · ${row.reason}" else row.reason)
            }.sortedByDescending { it.score }
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
