package com.pod4me.data

import com.pod4me.domain.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FeedClient {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    suspend fun fetch(url: String): FeedParser.Feed = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(FeedParser.httpUrl(url)).header("User-Agent", "Pod4Me/0.1")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Feed server returned HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "Feed server returned no content" }
            val bytes = body.byteStream().use { it.readNBytes(FeedParser.MAX_BYTES + 1) }
            FeedParser.parse(bytes, response.request.url.toString())
        }
    }
}
