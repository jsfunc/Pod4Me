package com.pod4me.data

import android.app.Application
import com.pod4me.domain.Feed
import com.pod4me.domain.FeedParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeedRefreshTest {
    private fun feed(id: String = "1") = Feed(id, "https://example.com/$id", "Feed $id", 30)
    @Test fun conditionalRequestAcceptsEmpty304AndPreservesValidators() = runBlocking {
        val original = feed().copy(etag = "\"v1\"", lastModified = "Mon, 02 Nov 2026 10:00:00 GMT")
        val requests = mutableListOf<Request>()
        val client = FeedClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(304).message("Not Modified")
                .body("".toResponseBody()).build()
        }.build())
        val response = client.refresh(original)
        assertEquals(original.etag, requests.single().header("If-None-Match"))
        assertEquals(original.lastModified, requests.single().header("If-Modified-Since"))
        assertNull(response.parsed); assertEquals(original.etag, response.etag); assertEquals(original.lastModified, response.lastModified)
        try { client.fetch(original.url); fail("Unconditional 304 must fail") } catch (_: IllegalStateException) { }
    }
    @Test fun followsOnlyPermanentRedirectPrefixAndParsesChangedFeed() = runBlocking {
        for ((codes, expected) in listOf(listOf(301, 308) to "https://example.com/final", listOf(301, 302) to "https://example.com/middle", listOf(302, 301) to null)) {
            val client = FeedClient(OkHttpClient.Builder().addInterceptor { chain ->
                fun response(url: String, code: Int, prior: Response? = null) = Response.Builder()
                    .request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(code).message("Test").priorResponse(prior)
                val first = response(chain.request().url.toString(), codes[0]).build()
                val middle = response("https://example.com/middle", codes[1], first).build()
                response("https://example.com/final", 200, middle).header("ETag", "v2")
                    .body("<rss><channel><title>Changed</title></channel></rss>".toResponseBody()).build()
            }.build())
            val result = client.refresh(feed())
            assertEquals(expected, result.permanentUrl); assertEquals("Changed", result.parsed!!.title()); assertEquals("v2", result.etag)
        }
    }
    @Test fun refreshBoundsConcurrencyIsolatesFailureAndExcludesChannels() = runBlocking {
        var active = 0; var peak = 0
        val saved = mutableListOf<String>(); val failures = mutableListOf<String>(); val progress = mutableListOf<Int>()
        val refresher = FeedRefresher(fetch = { target ->
            active++; peak = maxOf(peak, active)
            try { delay(20); if (target.id == "3") throw IOException("Offline")
                FeedResponse(FeedParser.Feed("Feed", emptyList()), null, null)
            } finally { active-- }
        }, save = { target, _ -> saved += target.id }, fail = { target, _ -> failures += target.id })
        val feeds = (1..9).map { feed(it.toString()) } + feed("removed").copy(subscribed = false) +
            feed("yt").copy(url = "https://www.youtube.com/@channel")
        val result = refresher.refresh(feeds) { done, total -> assertEquals(9, total); progress += done }
        assertEquals(4, peak); assertEquals(8, saved.size); assertEquals(listOf("3"), failures)
        assertEquals(listOf("3"), result.map { it.id }); assertEquals((0..9).toList(), progress)
    }
    @Test fun cancellingRefreshDoesNotRecordFailuresOrSavePartialResults() = runBlocking {
        var failed = false; var saved = false
        val started = CompletableDeferred<Unit>()
        val refresher = FeedRefresher({ started.complete(Unit); awaitCancellation() }, { _, _ -> saved = true }, { _, _ -> failed = true })
        val job = launch { refresher.refresh((1..8).map { feed(it.toString()) }) { _, _ -> } }
        started.await(); job.cancelAndJoin()
        assertFalse(failed); assertFalse(saved)
    }
}
