package com.pod4me.data

import com.pod4me.domain.SubscriptionFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Independent imports, at most four in flight. A failure never rolls back other subscriptions. */
internal class SubscriptionImporter(private val subscribe: suspend (SubscriptionFormat.Entry) -> Boolean) {
    data class Result(val added: Int, val skipped: Int, val failures: List<String>)
    private data class EntryResult(val added: Boolean = false, val failure: String? = null)

    suspend fun run(entries: List<SubscriptionFormat.Entry>, progress: (Int, Int) -> Unit): Result = coroutineScope {
        val slots = Semaphore(4)
        val progressLock = Mutex()
        var completed = 0
        progress(0, entries.size)
        val results = entries.map { entry -> async {
            slots.withPermit {
                try { EntryResult(added = subscribe(entry)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { EntryResult(failure = "${entry.url()}: ${e.message ?: "Import failed"}") }
                finally { progressLock.withLock { progress(++completed, entries.size) } }
            }
        } }.awaitAll()
        Result(results.count { it.added }, results.count { !it.added && it.failure == null }, results.mapNotNull { it.failure })
    }
}
