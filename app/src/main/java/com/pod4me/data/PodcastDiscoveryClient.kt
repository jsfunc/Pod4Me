package com.pod4me.data

import android.os.SystemClock
import com.pod4me.BuildConfig
import com.pod4me.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DiscoveryResult(val items: List<PodcastRecommendation>, val partial: Boolean)

/** Shared bounded/rate-limited Apple directory transport for suggestions and explicit searches. */
class PodcastDiscoveryClient {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private data class Cached(val at: Long, val json: JSONObject)
    private val cache = linkedMapOf<String, Cached>()
    private var lastRequest = -3100L
    private val requests = Mutex()

    suspend fun search(term: String): List<PodcastRecommendation> = withContext(Dispatchers.IO) {
        require(term.isNotBlank() && term.length <= 256) { "Enter a podcast name (up to 256 characters)" }
        val country = Locale.getDefault().country.takeIf { it.matches(Regex("[A-Za-z]{2}")) } ?: "US"
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term.trim()).addQueryParameter("entity", "podcast")
            .addQueryParameter("country", country).addQueryParameter("limit", "30").build().toString()
        parseSearch(json(url), "").distinctBy { it.feedUrl }
    }
    suspend fun resolveAppleUrl(url: String): String? = withContext(Dispatchers.IO) {
        val id = applePodcastId(url) ?: return@withContext null
        val endpoint = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder().addQueryParameter("id", id)
            .addQueryParameter("entity", "podcast").build().toString()
        parseSearch(json(endpoint), "").firstOrNull()?.feedUrl ?: error("This Apple link has no public podcast feed")
    }

    suspend fun discover(library: Library, memory: DiscoveryMemory): DiscoveryResult = withContext(Dispatchers.IO) {
        val country = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"
        val related = mutableListOf<PodcastRecommendation>()
        val popular = mutableListOf<PodcastRecommendation>()
        var failed = false
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        for (query in PodcastDiscovery.queries(library, feedback = memory.feedback)) attempt {
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", query.term).addQueryParameter("entity", "podcast")
                .addQueryParameter("country", country).addQueryParameter("limit", "30").build().toString()
            related += parseSearch(json(url), "Related search: ${query.source}").mapIndexed { index, item ->
                item.copy(score = 30 - index + 8 * PodcastDiscovery.words(item.title + " " + item.publisher).count { it in PodcastDiscovery.words(query.term) })
            }
        }
        attempt {
            val chart = json("https://itunes.apple.com/$country/rss/toppodcasts/limit=50/json")
                .getJSONObject("feed").getJSONArray("entry")
            val ranks = (0 until chart.length()).mapNotNull { index ->
                chart.getJSONObject(index).getJSONObject("id").getJSONObject("attributes").optString("im:id")
                    .takeIf { it.matches(Regex("[0-9]+")) }?.let { it to index }
            }.toMap()
            if (ranks.isNotEmpty()) {
                val lookup = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                    .addQueryParameter("id", ranks.keys.joinToString(",")).addQueryParameter("entity", "podcast")
                    .addQueryParameter("country", country).build().toString()
                val results = json(lookup).getJSONArray("results")
                for (i in 0 until results.length()) {
                    val row = results.getJSONObject(i)
                    val rank = ranks[row.optString("collectionId")] ?: continue
                    parsePodcast(row, "Popular in ${country.uppercase(Locale.ROOT)} · #${rank + 1}")?.let { popular += it.copy(score = 50 - rank) }
                }
            }
        }
        val items = PodcastDiscovery.select(related, popular, library.subscriptions, memory.recent.toSet(),
            previous = memory.lastShown, feedback = memory.feedback)
        check(items.isNotEmpty()) { if (failed) "Could not find fresh suggestions. Check your connection and tap Suggest again."
            else "No different suggestions available for these searches right now. Try Suggest later; the previous batch will not be repeated." }
        DiscoveryResult(items, failed)
    }

    private suspend fun json(url: String): JSONObject = requests.withLock {
        currentCoroutineContext().ensureActive()
        val now = SystemClock.elapsedRealtime()
        cache[url]?.takeIf { now - it.at < 10 * 60_000L }?.let { return@withLock it.json }
        // Apple's published approximate limit is 20 calls/minute. Cached results are reranked on each tap.
        delay((3100 - (now - lastRequest)).coerceAtLeast(0))
        lastRequest = SystemClock.elapsedRealtime()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Pod4Me/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json").build())
        val result = call.execute().use { response ->
            check(response.isSuccessful) { "Directory returned HTTP ${response.code}" }
            val bytes = requireNotNull(response.body).byteStream().use { it.readNBytes(2_097_153) }
            require(bytes.size <= 2_097_152) { "Directory response is too large" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        currentCoroutineContext().ensureActive()
        cache[url] = Cached(SystemClock.elapsedRealtime(), result)
        while (cache.size > 40) cache.remove(cache.keys.first())
        result
    }

    companion object {
        internal fun applePodcastId(url: String): String? {
            val uri = java.net.URI(FeedParser.httpUrl(url))
            if (uri.host.lowercase(Locale.ROOT) !in setOf("podcasts.apple.com", "itunes.apple.com")) return null
            return Regex("(?:^|/)id([0-9]+)(?:/|$)").find(uri.path.orEmpty())?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Choose an Apple podcast link containing its id")
        }
        internal fun parseSearch(json: JSONObject, reason: String): List<PodcastRecommendation> {
            val rows = json.getJSONArray("results")
            return (0 until rows.length()).mapNotNull { parsePodcast(rows.getJSONObject(it), reason) }
        }
        internal fun parsePodcast(row: JSONObject, reason: String): PodcastRecommendation? = runCatching {
            require(row.optString("kind") == "podcast")
            val feed = FeedParser.httpUrl(row.getString("feedUrl"))
            val apple = FeedParser.httpUrl(row.optString("collectionViewUrl", row.optString("trackViewUrl")))
            val host = java.net.URI(apple).host.lowercase(Locale.ROOT)
            require(host == "podcasts.apple.com" || host == "itunes.apple.com")
            val title = row.getString("collectionName").trim().take(512)
            require(title.isNotEmpty())
            val genres = row.optJSONArray("genres")
            val topics = if (genres == null) emptyList() else (0 until minOf(genres.length(), 8))
                .map { genres.optString(it).trim().take(100) }.filter { it.isNotEmpty() && !it.equals("Podcasts", ignoreCase = true) }.distinct()
            PodcastRecommendation(title, row.optString("artistName").take(512), feed, apple, reason, topics = topics,
                artworkUrl = row.optString("artworkUrl100").takeIf(String::isNotBlank)?.let { runCatching { FeedParser.httpUrl(it) }.getOrNull() })
        }.getOrNull()
    }
}
