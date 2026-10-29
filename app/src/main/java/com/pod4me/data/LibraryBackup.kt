package com.pod4me.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonWriter
import com.pod4me.domain.FeedParser
import com.pod4me.domain.Policy
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Versioned, streaming metadata backup. Never accepts file paths or DownloadManager IDs. */
internal object LibraryBackup {
    private val tables = linkedMapOf(
        "feeds" to "id,url,name,expiry_days,artwork_url,subscribed",
        "lists" to "id,name,system",
        "episodes" to "id,feed_id,guid,title,audio_url,description,published,duration,stars,position,heard",
        "feed_defaults" to "feed_id,list_id",
        "memberships" to "episode_id,list_id",
        "bookmarks" to "episode_id,position,name",
        "queue" to "episode_id,ordinal",
    )
    private val numeric = setOf("expiry_days", "subscribed", "system", "published", "duration", "stars", "position", "heard", "ordinal")
    private val nullable = setOf("expiry_days", "artwork_url")
    const val MAX_BYTES = 256L * 1024 * 1024

    fun export(db: SQLiteDatabase, context: Context, output: OutputStream) {
        JsonWriter(output.writer(Charsets.UTF_8)).use { json ->
            json.beginObject().name("format").value("Pod4Me").name("version").value(1)
            for ((table, columns) in tables) {
                json.name(table).beginArray()
                db.rawQuery("SELECT $columns FROM $table" + if (table == "queue") " ORDER BY ordinal" else "", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        json.beginObject()
                        cursor.columnNames.forEachIndexed { index, name ->
                            json.name(name)
                            if (cursor.isNull(index)) json.nullValue()
                            else if (name in numeric) json.value(cursor.getLong(index))
                            else json.value(cursor.getString(index))
                        }
                        json.endObject()
                    }
                }
                json.endArray()
            }
            val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
            json.name("playback").beginObject().name("speed").value(prefs.getFloat("speed", 1f).toDouble())
                .name("current").value(prefs.getString("current", ""))
                .name("order").value(prefs.getString("order", "[]"))
                .name("cursor").value(prefs.getInt("cursor", -1).toLong()).endObject().endObject()
        }
    }

    data class Playback(val speed: Float, val current: String?, val order: List<String>, val cursor: Int)

    /** Caller owns the transaction. Imports merge by feed URL, episode GUID, and list name. */
    fun restore(db: SQLiteDatabase, input: InputStream): Playback {
        val feeds = mutableMapOf<String, String>()
        val lists = mutableMapOf<String, String>()
        val episodes = mutableMapOf<String, String>()
        var rowCount = 0
        JsonReader(input.reader(Charsets.UTF_8)).use { json ->
            json.beginObject()
            require(json.nextName() == "format" && json.nextString() == "Pod4Me") { "This is not a Pod4Me library backup" }
            require(json.nextName() == "version" && json.nextInt() == 1) { "This backup version is not supported" }
            for ((table, columns) in tables) {
                require(json.nextName() == table) { "Backup is incomplete or has an invalid table order" }
                json.beginArray()
                while (json.hasNext()) {
                    require(++rowCount <= 500_000) { "Backup contains too many records" }
                    val row = ContentValues()
                    val expected = columns.split(',').toSet()
                    json.beginObject()
                    while (json.hasNext()) {
                        val name = json.nextName()
                        require(name in expected && !row.containsKey(name)) { "Unexpected or duplicate backup field: $name" }
                        if (json.peek() == android.util.JsonToken.NULL) {
                            require(name in nullable) { "Missing backup value: $name" }; json.nextNull(); row.putNull(name)
                        } else if (name in numeric) row.put(name, json.nextLong())
                        else {
                            val value = json.nextString()
                            require(value.length <= if (name == "description") 8_388_608 else 65_536) { "Backup field is too long: $name" }
                            row.put(name, value)
                        }
                    }
                    json.endObject()
                    require(row.keySet() == expected) { "Backup record is missing fields" }
                    fun text(key: String) = row.getAsString(key)
                    fun number(key: String) = row.getAsLong(key)
                    fun reference(key: String, map: Map<String, String>) {
                        row.put(key, requireNotNull(map[text(key)]) { "Backup references a missing $key" })
                    }
                    fun flag(key: String) { require(number(key) in 0L..1L) { "Invalid backup flag: $key" } }
                    when (table) {
                        "feeds" -> {
                            val original = text("id"); require(original !in feeds) { "Duplicate feed ID in backup" }
                            val url = text("url")
                            if (url.isNotEmpty()) FeedParser.httpUrl(url)
                            require(text("name").isNotBlank()) { "Feed name cannot be empty" }
                            val days = row.getAsLong("expiry_days")
                            require(days == null || days in 1L..36500L) { "Invalid feed expiry" }
                            Policy.validateExpiryDays(days?.toInt()); flag("subscribed")
                            row.getAsString("artwork_url")?.let(FeedParser::httpUrl)
                            val id = find(db, "feeds", "url=?", url) ?: if (url.isEmpty()) "shared" else UUID.randomUUID().toString()
                            require(id !in feeds.values) { "Duplicate feed URL in backup" }
                            feeds[original] = id; row.put("id", id)
                            upsert(db, table, row, id)
                        }
                        "lists" -> {
                            val original = text("id"); require(original !in lists) { "Duplicate list ID in backup" }
                            val system = original in setOf("unheard", "heard", "4ever")
                            flag("system"); require((number("system") == 1L) == system) { "Invalid system list" }
                            require(text("name").isNotBlank()) { "List name cannot be empty" }
                            if (system) lists[original] = original
                            else {
                                val id = find(db, "lists", "name=? COLLATE NOCASE", text("name")) ?: UUID.randomUUID().toString()
                                require(id !in setOf("unheard", "heard", "4ever")) { "Custom list conflicts with a system list" }
                                lists[original] = id; row.put("id", id); upsert(db, table, row, id)
                            }
                        }
                        "episodes" -> {
                            val original = text("id"); require(original !in episodes) { "Duplicate episode ID in backup" }
                            reference("feed_id", feeds); FeedParser.httpUrl(text("audio_url"))
                            require(number("stars") in 0L..5L && number("position") >= 0 && number("duration") >= 0) { "Invalid episode progress or rating" }
                            flag("heard")
                            val id = find(db, table, "feed_id=? AND guid=?", text("feed_id"), text("guid")) ?: UUID.randomUUID().toString()
                            require(id !in episodes.values) { "Duplicate episode GUID in backup" }
                            episodes[original] = id; row.put("id", id)
                            upsert(db, table, row, id) // Leaves existing audio and pending downloads intact.
                        }
                        "feed_defaults", "memberships" -> {
                            reference("list_id", lists)
                            require(text("list_id") !in setOf("heard", "unheard")) { "Invalid system membership" }
                            if (table == "feed_defaults") reference("feed_id", feeds) else reference("episode_id", episodes)
                            db.insertWithOnConflict(table, null, row, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        "bookmarks" -> {
                            reference("episode_id", episodes)
                            require(number("position") >= 0) { "Invalid bookmark position" }
                            val exists = db.rawQuery("SELECT 1 FROM bookmarks WHERE episode_id=? AND position=? AND name=?",
                                arrayOf(text("episode_id"), number("position").toString(), text("name"))).use { it.moveToFirst() }
                            if (!exists) db.insertOrThrow(table, null, row)
                        }
                        "queue" -> {
                            reference("episode_id", episodes)
                            db.execSQL("INSERT OR IGNORE INTO queue VALUES(?,(SELECT COALESCE(MAX(ordinal),0)+1 FROM queue))", arrayOf(text("episode_id")))
                        }
                    }
                }
                json.endArray()
            }
            require(lists.keys.containsAll(setOf("unheard", "heard", "4ever"))) { "Backup is missing the system lists" }
            require(json.nextName() == "playback") { "Backup is missing playback settings" }
            json.beginObject()
            require(json.nextName() == "speed"); val speed = json.nextDouble().toFloat()
            require(speed.isFinite() && speed in 1f..3f) { "Invalid playback speed" }
            require(json.nextName() == "current"); val current = episodes[json.nextString()]
            require(json.nextName() == "order"); val rawOrder = json.nextString()
            require(rawOrder.length <= 4_000_000) { "Playback order is too large" }
            val array = JSONArray(rawOrder)
            val order = (0 until array.length()).mapNotNull { episodes[array.getString(it)] }.distinct()
            require(json.nextName() == "cursor"); val cursor = json.nextInt()
            json.endObject(); json.endObject()
            require(json.peek() == android.util.JsonToken.END_DOCUMENT) { "Unexpected data after backup" }
            return Playback(speed, current, order, cursor.coerceIn(-1, order.lastIndex))
        }
    }

    private fun find(db: SQLiteDatabase, table: String, where: String, vararg args: String): String? =
        db.rawQuery("SELECT id FROM $table WHERE $where", args).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun upsert(db: SQLiteDatabase, table: String, row: ContentValues, id: String) {
        if (db.update(table, row, "id=?", arrayOf(id)) == 0) db.insertOrThrow(table, null, row)
    }
}
