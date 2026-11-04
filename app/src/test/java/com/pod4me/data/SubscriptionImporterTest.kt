package com.pod4me.data

import com.pod4me.domain.SubscriptionFormat
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SubscriptionImporterTest {
    private fun entries() = (1..10).map { SubscriptionFormat.Entry("https://example.com/$it", "Show $it") }

    @Test fun boundsConcurrencyReportsEveryAttemptAndKeepsIndependentSuccesses() = runBlocking {
        var active = 0
        var peak = 0
        val saved = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val importer = SubscriptionImporter { entry ->
            active++; peak = maxOf(peak, active)
            try {
                delay(10)
                when (entry.title()) {
                    "Show 3" -> throw IOException("Offline")
                    "Show 4" -> false
                    else -> { saved += entry.url(); true }
                }
            } finally { active-- }
        }
        val result = importer.run(entries()) { done, total -> assertEquals(10, total); progress += done }
        assertEquals(4, peak)
        assertEquals(8, saved.size); assertEquals(8, result.added); assertEquals(1, result.skipped)
        assertEquals(listOf("https://example.com/3: Offline"), result.failures)
        assertEquals((0..10).toList(), progress)
    }

    @Test fun cancellationKeepsCompletedEntriesAndCancelsRemainingWork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        var active = 0
        val importer = SubscriptionImporter { entry ->
            if (entry.title() == "Show 1") { saved += entry.url(); true }
            else {
                active++
                try { started.complete(Unit); awaitCancellation() } finally { active-- }
            }
        }
        val job = launch { importer.run(entries()) { _, _ -> } }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(0, active)
        assertEquals(listOf("https://example.com/1"), saved)
    }
}
