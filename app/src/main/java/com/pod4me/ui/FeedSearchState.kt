package com.pod4me.ui

import androidx.compose.runtime.*
import com.pod4me.domain.FeedSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class FeedSearchUi(val loading: Boolean = false, val ids: List<String> = emptyList(), val error: String? = null)
private data class SearchIndex(val documents: List<FeedSearch.Document> = emptyList(), val error: String? = null)

/** Scoped to the visible feed; progress ticks don't rebuild it. Old queries cannot publish stale results. */
@Composable
internal fun rememberFeedSearch(model: AppModel, feedId: String?, revision: Long, query: String): FeedSearchUi {
    if (feedId == null) return FeedSearchUi()
    return key(model, feedId, revision) {
        val index by produceState<SearchIndex?>(null) {
            value = try { SearchIndex(model.store.feedSearchIndex(feedId)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { SearchIndex(error = e.message ?: "Could not search this feed") }
        }
        key(query, index) {
            val results by produceState(FeedSearchUi(loading = query.isNotBlank())) {
                val loaded = index
                if (query.isBlank()) value = FeedSearchUi()
                else if (loaded != null) {
                    if (loaded.error != null) value = FeedSearchUi(error = loaded.error)
                    else {
                        delay(250)
                        value = FeedSearchUi(ids = withContext(Dispatchers.Default) { FeedSearch.rank(loaded.documents, query) })
                    }
                }
            }
            results
        }
    }
}
