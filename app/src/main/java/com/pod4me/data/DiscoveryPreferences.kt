package com.pod4me.data

import android.content.Context
import com.pod4me.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Local discovery preferences, separate from episode ratings and the portable library backup. */
class DiscoveryPreferences(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var loaded = false
    private val mutableState = MutableStateFlow(DiscoveryMemory())
    val state = mutableState.asStateFlow()

    suspend fun read(): DiscoveryMemory = update { it }

    /** Tapping the current thumb clears it; the other thumb replaces it. */
    suspend fun rate(item: PodcastRecommendation, vote: Int) = update { memory ->
        require(vote == -1 || vote == 1)
        val key = PodcastDiscovery.feedKey(item.feedUrl)
        val ratings = memory.feedback.toMutableMap()
        if (ratings[key]?.vote == vote) ratings.remove(key)
        else ratings[key] = PodcastFeedback(vote, item.title, item.publisher, item.topics)
        memory.copy(feedback = ratings)
    }

    suspend fun rememberShown(items: List<PodcastRecommendation>) = update { memory ->
        if (items.isEmpty()) memory else {
            val shown = items.map { PodcastDiscovery.feedKey(it.feedUrl) }.toSet()
            memory.copy(lastShown = shown, recent = (memory.recent.filter { it !in shown } + shown).takeLast(100))
        }
    }

    private suspend fun update(change: (DiscoveryMemory) -> DiscoveryMemory): DiscoveryMemory = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = app.getSharedPreferences("podcast_discovery", Context.MODE_PRIVATE)
            if (!loaded) {
                mutableState.value = prefs.getString("memory", null)?.let(::decode) ?: DiscoveryMemory()
                loaded = true
            }
            val next = change(mutableState.value)
            if (next != mutableState.value) {
                check(prefs.edit().putString("memory", encode(next)).commit()) { "Could not save podcast preferences. Please try again." }
                mutableState.value = next
            }
            next
        }
    }

    private fun encode(memory: DiscoveryMemory): String {
        val ratings = JSONArray()
        memory.feedback.forEach { (key, rating) -> ratings.put(JSONObject().put("key", key)
            .put("vote", rating.vote).put("title", rating.title).put("publisher", rating.publisher)
            .put("topics", JSONArray(rating.topics))) }
        return JSONObject().put("version", 1).put("ratings", ratings)
            .put("last", JSONArray(memory.lastShown.toList())).put("recent", JSONArray(memory.recent)).toString()
    }

    private fun decode(text: String): DiscoveryMemory {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "Unsupported podcast preference version" }
        val ratings = json.getJSONArray("ratings")
        val feedback = buildMap {
            for (i in 0 until ratings.length()) {
                val row = ratings.getJSONObject(i)
                val vote = row.getInt("vote")
                require(vote == -1 || vote == 1)
                put(row.getString("key"), PodcastFeedback(vote, row.getString("title"), row.getString("publisher"), strings(row.getJSONArray("topics"))))
            }
        }
        return DiscoveryMemory(feedback, strings(json.getJSONArray("last")).toSet(), strings(json.getJSONArray("recent")))
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
}
