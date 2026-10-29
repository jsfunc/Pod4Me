package com.pod4me.data

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import com.pod4me.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "pod4me.db", null, 2) {
    private val app = context.applicationContext
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) { executeAsset(db, "schema.sql") }
    private fun executeAsset(db: SQLiteDatabase, name: String) {
        app.assets.open(name).bufferedReader().use { it.readText() }
            .split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) executeAsset(db, "migration_1_2.sql")
        else error("A migration from $oldVersion to $newVersion is required")
    }
}

/** All mutations, deletion guards and snapshots pass through this one serialized boundary. */
class LibraryStore(private val context: Context) {
    private val helper = LibraryDatabase(context)
    private val mutex = Mutex()
    private val mutableLibrary = MutableStateFlow(Library())
    val library = mutableLibrary.asStateFlow()
    val activeEpisode = AtomicReference<String?>(null)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val directory: File get() = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)) { "Download storage is unavailable" }

    private suspend fun <T> write(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            val result = try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            mutableLibrary.value = snapshot(db)
            result
        }
    }
    suspend fun initialize() = write { Unit }

    suspend fun saveFeed(url: String, parsed: FeedParser.Feed, existingId: String? = null): String = write { db ->
        val known = db.rows("SELECT id FROM feeds WHERE url=?", url) { it.str("id") }.firstOrNull()
        val id = existingId ?: known ?: UUID.randomUUID().toString()
        if (existingId == null && known == null) db.insertOrThrow("feeds", null,
            values("id" to id, "url" to url, "name" to parsed.title(), "expiry_days" to 30))
        db.update("feeds", values("error" to null, "artwork_url" to parsed.artworkUrl().takeIf { it.isNotBlank() }), "id=?", arrayOf(id))
        for (item in parsed.items()) {
            val episodeId = db.rows("SELECT id FROM episodes WHERE feed_id=? AND guid=?", id, item.guid()) { it.str("id") }.firstOrNull()
            val metadata = values("title" to item.title(), "audio_url" to item.audioUrl(), "description" to item.description(), "published" to item.published())
            if (episodeId == null) {
                metadata.put("id", UUID.randomUUID().toString()); metadata.put("feed_id", id)
                metadata.put("guid", item.guid()); metadata.put("duration", item.duration())
                db.insertOrThrow("episodes", null, metadata)
            } else db.update("episodes", metadata, "id=?", arrayOf(episodeId))
        }
        id
    }
    suspend fun feedError(id: String, error: String) = write { db -> db.update("feeds", values("error" to error), "id=?", arrayOf(id)) }
    suspend fun editFeed(id: String, name: String, days: Int?, defaults: Set<String>) = write { db ->
        require(name.isNotBlank()) { "Feed name cannot be empty" }
        Policy.downloadExpiry(0, days)
        db.update("feeds", values("name" to name.trim(), "expiry_days" to days), "id=?", arrayOf(id))
        db.delete("feed_defaults", "feed_id=?", arrayOf(id))
        defaults.filter { it != "heard" && it != "unheard" }.forEach {
            db.insertOrThrow("feed_defaults", null, values("feed_id" to id, "list_id" to it))
        }
    }
    suspend fun addSharedAudio(url: String, title: String, feedId: String? = null): String = write { db ->
        db.rows("SELECT id FROM episodes WHERE audio_url=?", url) { it.str("id") }.firstOrNull() ?: run {
            val targetFeed = feedId ?: "shared"
            if (feedId == null) db.insertWithOnConflict("feeds", null,
                values("id" to "shared", "url" to "", "name" to "Shared episodes", "expiry_days" to 30), SQLiteDatabase.CONFLICT_IGNORE)
            val id = UUID.randomUUID().toString()
            db.insertOrThrow("episodes", null, values("id" to id, "feed_id" to targetFeed, "guid" to url,
                "title" to title.ifBlank { Uri.parse(url).lastPathSegment ?: "Shared episode" }, "audio_url" to url,
                "description" to "Shared audio: $url", "published" to 0L))
            id
        }
    }
    suspend fun createList(name: String) = write { db ->
        require(name.isNotBlank()) { "List name cannot be empty" }
        db.insertOrThrow("lists", null, values("id" to UUID.randomUUID().toString(), "name" to name.trim()))
    }
    suspend fun renameList(id: String, name: String) = write { db ->
        require(name.isNotBlank()) { "List name cannot be empty" }
        require(id !in setOf("unheard", "heard", "4ever")) { "System lists cannot be renamed" }
        db.update("lists", values("name" to name.trim()), "id=?", arrayOf(id))
    }
    suspend fun deleteList(id: String) = write { db ->
        require(id !in setOf("unheard", "heard", "4ever")) { "System lists cannot be deleted" }
        db.delete("lists", "id=?", arrayOf(id))
    }
    suspend fun membership(id: String, list: String, included: Boolean) = write { db ->
        if (list == "unheard" || list == "heard") {
            val heard = if (list == "unheard") !included else included
            setHeard(db, id, heard, System.currentTimeMillis())
        } else if (included) db.insertWithOnConflict("memberships", null, values("episode_id" to id, "list_id" to list), SQLiteDatabase.CONFLICT_IGNORE)
        else db.delete("memberships", "episode_id=? AND list_id=?", arrayOf(id, list))
    }
    suspend fun rate(id: String, stars: Int) = write { db ->
        require(stars in 0..5)
        db.update("episodes", values("stars" to stars), "id=?", arrayOf(id))
    }
    suspend fun progress(id: String, position: Long, duration: Long) = write { db ->
        val previous = episode(db, id) ?: return@write
        val actualDuration = duration.takeIf { it > 0 } ?: previous.duration
        db.update("episodes", values("position" to position.coerceAtLeast(0), "duration" to actualDuration), "id=?", arrayOf(id))
        if (!previous.heard && Policy.isHeard(position, actualDuration)) setHeard(db, id, true, System.currentTimeMillis())
    }
    private fun setHeard(db: SQLiteDatabase, id: String, heard: Boolean, now: Long) {
        val ep = episode(db, id) ?: return
        val changes = values("heard" to if (heard) 1 else 0)
        if (heard && !ep.heard) changes.put("expiry", Policy.heardExpiry(ep.expiry, now))
        if (!heard) changes.put("position", 0L)
        db.update("episodes", changes, "id=?", arrayOf(id))
    }
    suspend fun bookmark(id: String, position: Long, name: String) = write { db ->
        db.insertOrThrow("bookmarks", null, values("episode_id" to id, "position" to position.coerceAtLeast(0), "name" to name.trim()))
    }
    suspend fun renameBookmark(id: Long, name: String) = write { db -> db.update("bookmarks", values("name" to name.trim()), "id=?", arrayOf(id.toString())) }
    suspend fun deleteBookmark(id: Long) = write { db -> db.delete("bookmarks", "id=?", arrayOf(id.toString())) }

    suspend fun download(id: String) = write { db ->
        val ep = requireNotNull(episode(db, id))
        if (ep.path != null || ep.downloadId != null) return@write
        FeedParser.httpUrl(ep.audioUrl)
        val file = File(directory, "$id.audio")
        if (file.exists()) check(file.delete()) { "Could not remove an incomplete download" }
        val request = DownloadManager.Request(Uri.parse(ep.audioUrl))
            .setTitle(ep.title).setDescription("Pod4Me")
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
        val downloadId = manager.enqueue(request)
        try { db.update("episodes", values("download_id" to downloadId, "error" to null), "id=?", arrayOf(id)) }
        catch (e: Exception) { manager.remove(downloadId); throw e }
    }
    suspend fun cancelDownload(id: String) = write { db ->
        val ep = episode(db, id) ?: return@write
        ep.downloadId?.let { manager.remove(it) }
        db.update("episodes", values("download_id" to null, "error" to null), "id=?", arrayOf(id))
    }
    suspend fun reconcileDownloads() = write { db ->
        val pending = db.rows("SELECT * FROM episodes WHERE download_id IS NOT NULL") { readEpisode(it) }
        for (ep in pending) {
            val downloadId = ep.downloadId ?: continue
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    db.update("episodes", values("download_id" to null, "error" to "Download was removed. Tap Download to retry."), "id=?", arrayOf(ep.id))
                    return@use
                }
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val file = File(directory, "${ep.id}.audio")
                        if (!file.isFile || file.length() == 0L) {
                            manager.remove(downloadId)
                            db.update("episodes", values("download_id" to null, "error" to "Downloaded file is missing or empty"), "id=?", arrayOf(ep.id))
                        } else {
                            val days = db.rows("SELECT expiry_days FROM feeds WHERE id=?", ep.feedId) { it.nullLong("expiry_days")?.toInt() }.firstOrNull()
                            val now = System.currentTimeMillis()
                            val deadline = Policy.downloadExpiry(now, days)
                            db.update("episodes", values("download_id" to null, "path" to file.absolutePath, "downloaded" to now,
                                "expiry" to if (ep.heard) Policy.heardExpiry(deadline, now) else deadline, "error" to null), "id=?", arrayOf(ep.id))
                            db.execSQL("INSERT OR IGNORE INTO memberships SELECT ?,list_id FROM feed_defaults WHERE feed_id=?", arrayOf(ep.id, ep.feedId))
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        manager.remove(downloadId)
                        db.update("episodes", values("download_id" to null, "error" to "Download failed ($reason). Tap Download to retry."), "id=?", arrayOf(ep.id))
                    }
                }
            }
        }
        // Repair metadata if Android or the user has removed a downloaded file.
        db.rows("SELECT * FROM episodes WHERE path IS NOT NULL") { readEpisode(it) }.forEach { ep ->
            if (!File(requireNotNull(ep.path)).isFile) clearDownload(db, ep.id)
        }
    }
    suspend fun deleteAudio(id: String) = write { db ->
        val ep = episode(db, id) ?: return@write
        require(!isProtected(db, id)) { "Remove this episode from 4ever before deleting it" }
        require(activeEpisode.get() != id) { "Stop this episode before deleting it" }
        deleteFile(ep)
        clearDownload(db, id)
    }
    suspend fun expireAtLaunch(now: Long = System.currentTimeMillis()) = write { db ->
        db.rows("SELECT * FROM episodes WHERE path IS NOT NULL AND expiry IS NOT NULL AND expiry<=?", now.toString()) { readEpisode(it) }.forEach { ep ->
            if (Policy.isExpired(ep.expiry, now, isProtected(db, ep.id), activeEpisode.get() == ep.id)) {
                try { deleteFile(ep); clearDownload(db, ep.id) }
                catch (e: Exception) { db.update("episodes", values("error" to "Could not delete expired audio: ${e.message}"), "id=?", arrayOf(ep.id)) }
            }
        }
    }
    private fun isProtected(db: SQLiteDatabase, id: String) = db.rows("SELECT 1 AS present FROM memberships WHERE episode_id=? AND list_id='4ever'", id) { true }.isNotEmpty()
    private fun deleteFile(ep: Episode) {
        ep.path?.let { path ->
            val file = File(path)
            check(file.canonicalFile.parentFile == directory.canonicalFile) { "Unexpected download path" }
            check(!file.exists() || file.delete()) { "The audio file could not be deleted" }
        }
        ep.downloadId?.let { manager.remove(it) }
    }
    private fun clearDownload(db: SQLiteDatabase, id: String) {
        db.update("episodes", values("path" to null, "downloaded" to null, "expiry" to null, "download_id" to null), "id=?", arrayOf(id))
        db.delete("queue", "episode_id=?", arrayOf(id))
    }
    suspend fun activate(id: String): Episode = write { db ->
        val ep = requireNotNull(episode(db, id)) { "Episode no longer exists" }
        require(ep.path != null && File(ep.path).isFile) { "Download this episode before playing it" }
        activeEpisode.set(id)
        ep
    }
    suspend fun enqueue(id: String) = write { db ->
        require(episode(db, id)?.isDownloaded == true) { "Download this episode first" }
        db.execSQL("INSERT OR IGNORE INTO queue VALUES(?,(SELECT COALESCE(MAX(ordinal),0)+1 FROM queue))", arrayOf(id))
    }
    suspend fun removeFromQueue(id: String) = write { db -> db.delete("queue", "episode_id=?", arrayOf(id)) }
    suspend fun moveQueue(id: String, offset: Int) = write { db ->
        val ids = db.rows("SELECT episode_id FROM queue ORDER BY ordinal") { it.str("episode_id") }.toMutableList()
        val from = ids.indexOf(id)
        if (from >= 0) { ids.removeAt(from); ids.add((from + offset).coerceIn(0, ids.size), id) }
        ids.forEachIndexed { index, key -> db.update("queue", values("ordinal" to index), "episode_id=?", arrayOf(key)) }
    }
    suspend fun takeQueue(): String? = write { db ->
        val id = db.rows("SELECT q.episode_id FROM queue q JOIN episodes e ON e.id=q.episode_id WHERE e.path IS NOT NULL ORDER BY ordinal LIMIT 1") { it.str("episode_id") }.firstOrNull()
        if (id != null) db.delete("queue", "episode_id=?", arrayOf(id))
        id
    }

    private fun episode(db: SQLiteDatabase, id: String) = db.rows("SELECT * FROM episodes WHERE id=?", id) { readEpisode(it) }.firstOrNull()
    private fun snapshot(db: SQLiteDatabase): Library {
        val memberships = db.rows("SELECT episode_id,list_id FROM memberships") { it.str("episode_id") to it.str("list_id") }.groupBy({ it.first }, { it.second })
        val defaults = db.rows("SELECT feed_id,list_id FROM feed_defaults") { it.str("feed_id") to it.str("list_id") }.groupBy({ it.first }, { it.second })
        return Library(true,
            db.rows("SELECT * FROM feeds ORDER BY name COLLATE NOCASE") { Feed(it.str("id"), it.str("url"), it.str("name"), it.nullLong("expiry_days")?.toInt(), defaults[it.str("id")]?.toSet() ?: emptySet(), it.nullStr("error"), it.nullStr("artwork_url")) },
            db.rows("SELECT * FROM episodes") { readEpisode(it).let { ep -> ep.copy(lists = memberships[ep.id]?.toSet() ?: emptySet()) } },
            db.rows("SELECT * FROM lists ORDER BY system DESC,name COLLATE NOCASE") { TrackList(it.str("id"), it.str("name"), it.long("system") == 1L) },
            db.rows("SELECT * FROM bookmarks ORDER BY position") { Bookmark(it.long("id"), it.str("episode_id"), it.long("position"), it.str("name")) },
            db.rows("SELECT episode_id FROM queue ORDER BY ordinal") { it.str("episode_id") },
        )
    }
    private fun readEpisode(c: Cursor) = Episode(c.str("id"), c.str("feed_id"), c.str("title"), c.str("audio_url"), c.str("description"),
        c.long("published"), c.long("duration"), c.nullLong("downloaded"), c.nullStr("path"), c.nullLong("expiry"), c.long("stars").toInt(),
        c.long("position"), c.long("heard") == 1L, c.nullLong("download_id"), c.nullStr("error"))
}

private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.nullStr(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.nullLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
private fun <T> SQLiteDatabase.rows(sql: String, vararg args: String, read: (Cursor) -> T): List<T> =
    rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
    pairs.forEach { (key, value) -> when (value) {
        null -> putNull(key)
        is String -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        else -> error("Unsupported column type")
    } }
}
