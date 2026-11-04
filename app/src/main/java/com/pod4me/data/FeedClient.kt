package com.pod4me.data

import com.pod4me.domain.Feed
import com.pod4me.domain.FeedParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

data class FeedResponse(val parsed: FeedParser.Feed?, val etag: String?, val lastModified: String?,
    val permanentUrl: String? = null, val allowAddressChange: Boolean = true)

class FeedClient(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()) {
    suspend fun fetch(url: String): FeedParser.Feed = requireNotNull(request(url).parsed)
    suspend fun refresh(feed: Feed): FeedResponse = request(feed.url, feed.etag, feed.lastModified)

    private suspend fun request(url: String, etag: String? = null, modified: String? = null): FeedResponse = withContext(Dispatchers.IO) {
        coroutineScope {
            val request = Request.Builder().url(FeedParser.httpUrl(url)).header("User-Agent", "Pod4Me/${com.pod4me.BuildConfig.VERSION_NAME}")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            etag?.let { request.header("If-None-Match", it) }
            modified?.let { request.header("If-Modified-Since", it) }
            val call = client.newCall(request.build())
            // Cancellation can interrupt a blocking execute/body read, including during XML parsing.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    // A later HTTPS hop cannot restore trust in redirects or XML received over HTTP.
                    val allowAddressChange = !call.request().url.isHttps ||
                        generateSequence(response) { it.priorResponse }.all { it.request.url.isHttps }
                    val moved = permanentRedirect(response)
                    if (response.code == 304) {
                        check(etag != null || modified != null) { "Feed returned Not Modified without a cached version" }
                        FeedResponse(null, response.header("ETag") ?: etag, response.header("Last-Modified") ?: modified, moved, allowAddressChange)
                    } else {
                        check(response.isSuccessful) { "Feed server returned HTTP ${response.code}" }
                        val body = requireNotNull(response.body) { "Feed server returned no content" }
                        val parsed = body.byteStream().use { FeedParser.parse(it, response.request.url.toString()) }
                        FeedResponse(parsed, response.header("ETag"), response.header("Last-Modified"), moved, allowAddressChange)
                    }
                }
            } finally { cancellation.cancel() }
        }
    }

    private fun permanentRedirect(response: Response): String? {
        val chain = generateSequence(response) { it.priorResponse }.toList().asReversed()
        var destination: String? = null
        for (index in 0 until chain.lastIndex) {
            if (chain[index].code !in setOf(301, 308)) break
            destination = chain[index + 1].request.url.toString()
        }
        return destination
    }
}
