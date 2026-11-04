package com.pod4me.data

import com.pod4me.domain.Feed
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Four independent fetches, serialized commits in LibraryStore, failures isolated per feed. */
internal class FeedRefresher(
    private val fetch: suspend (Feed) -> FeedResponse,
    private val save: suspend (Feed, FeedResponse) -> Unit,
    private val fail: suspend (Feed, String) -> Unit,
) {
    suspend fun refresh(feeds: List<Feed>, progress: (Int, Int) -> Unit): List<Feed> = coroutineScope {
        val targets = feeds.filter { it.subscribed && it.url.isNotBlank() && !it.isYouTube }.distinctBy { it.id }
        val slots = Semaphore(4)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        progress(0, targets.size)
        targets.map { feed -> async {
            slots.withPermit {
                try { save(feed, fetch(feed)); null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(feed, e.message ?: "Refresh failed"); feed }
                finally { progress(completed.incrementAndGet(), targets.size) }
            }
        } }.awaitAll().filterNotNull()
    }
}
