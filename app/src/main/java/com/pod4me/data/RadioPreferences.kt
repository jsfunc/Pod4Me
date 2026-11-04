package com.pod4me.data

import android.content.Context
import com.pod4me.domain.RadioCatalogue
import com.pod4me.domain.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Personal station subscriptions; independent of podcast metadata and expiry. */
class RadioPreferences(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var loaded = false
    private val mutableState = MutableStateFlow<List<RadioStation>>(emptyList())
    val state = mutableState.asStateFlow()

    suspend fun read(): List<RadioStation> = update { it }
    suspend fun subscribe(name: String, url: String): List<RadioStation> {
        val station = RadioStation.create(name, url)
        return update { current ->
            if (current.any { it.url == station.url }) current
            else {
                require(current.size < 200) { "You can subscribe to up to 200 radio stations." }
                current + station
            }
        }
    }
    suspend fun unsubscribe(id: String) = update { current -> current.filterNot { it.id == id } }

    /** Save a drag result without resurrecting removals or losing subscriptions added meanwhile. */
    suspend fun reorder(ids: List<String>) = update { current ->
        val byId = current.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(byId::get)
        val included = ids.toSet()
        ordered + current.filterNot { it.id in included }
    }

    private suspend fun update(change: (List<RadioStation>) -> List<RadioStation>): List<RadioStation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = app.getSharedPreferences("live_radios", Context.MODE_PRIVATE)
            val saved = prefs.getString("stations", null)
            if (!loaded) {
                mutableState.value = if (saved == null) RadioCatalogue.defaults else {
                    val rows = JSONArray(saved)
                    (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
                        RadioStation.create(it.getString("name"), it.getString("url"))
                    } }.distinctBy { it.url }
                }
                loaded = true
            }
            val next = change(mutableState.value)
            if (saved == null || next != mutableState.value) {
                val rows = JSONArray()
                next.forEach { rows.put(JSONObject().put("name", it.name).put("url", it.url)) }
                check(prefs.edit().putString("stations", rows.toString()).commit()) { "Could not save radio subscriptions. Please try again." }
                mutableState.value = next
            }
            next
        }
    }
}
