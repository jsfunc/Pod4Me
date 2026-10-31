package com.pod4me.domain

import android.app.Application
import android.os.Environment
import com.pod4me.data.LibraryDatabase
import com.pod4me.data.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import android.app.DownloadManager
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryStoreTest {
    private lateinit var store: LibraryStore
    private lateinit var helper: LibraryDatabase
    private lateinit var file: File
    private lateinit var episode: Episode

    @After fun close() { helper.close(); store.close() }

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("pod4me.db")
        store = LibraryStore(context)
        store.initialize()
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Feed", listOf(
            FeedParser.Item("guid", "Episode", "https://example.com/audio.mp3", "Info", 100, 10_000))))
        episode = store.library.value.episodes.single()
        file = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${episode.id}.audio")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        helper = LibraryDatabase(context)
        helper.writableDatabase.execSQL("UPDATE episodes SET path=?,downloaded=1,expiry=2 WHERE id=?", arrayOf(file.absolutePath, episode.id))
        store.initialize()
    }

    @Test fun protectionGuardsBothManualAndAutomaticDeletion() = runBlocking {
        store.membership(episode.id, "4ever", true)
        store.expireAtLaunch(100)
        assertTrue(file.exists())
        try { store.deleteAudio(episode.id); fail("4ever must reject deletion") } catch (_: IllegalArgumentException) { }
        assertTrue(file.exists())
        store.membership(episode.id, "4ever", false)
        store.expireAtLaunch(100)
        assertFalse(file.exists())
        assertFalse(store.library.value.episodes.single().isDownloaded)
    }

    @Test fun feedSearchIncludesUndownloadedDescriptionsButNotOtherFeedsOrHtmlAttributes() = runBlocking {
        val feedId = store.saveFeed("https://example.com/search", FeedParser.Feed("Search", listOf(
            FeedParser.Item("notes", "Other title", "https://example.com/notes.mp3",
                "<p>Économie &amp; océan</p><a href='https://secretattribute.example'>Listen</a>", 10, 0),
            FeedParser.Item("title", "Économie océan", "https://example.com/title.mp3", "", 1, 0))))
        store.saveFeed("https://example.com/elsewhere", FeedParser.Feed("Other", listOf(
            FeedParser.Item("other", "Économie océan", "https://example.com/other.mp3", "", 100, 0))))
        val episodes = store.library.value.episodes.filter { it.feedId == feedId }
        assertTrue(episodes.all { !it.isDownloaded && it.description.isEmpty() })
        val index = store.feedSearchIndex(feedId)
        assertEquals(listOf(episodes.single { it.title == "Économie océan" }.id, episodes.single { it.title == "Other title" }.id),
            FeedSearch.rank(index, "ocean economie"))
        assertTrue(FeedSearch.rank(index, "secretattribute").isEmpty())
        assertTrue(store.library.value.episodes.all { it.description.isEmpty() })
    }

    @Test fun feedSearchReadsEveryBatchAndRefreshesDescriptionsWithoutLosingRetainedHistory() = runBlocking {
        val items = (1..70).map { FeedParser.Item("search-$it", "Episode $it", "https://example.com/$it.mp3", "astronomy", it.toLong(), 0) }
        val feedId = store.saveFeed("https://example.com/many", FeedParser.Feed("Many", items))
        val index = store.feedSearchIndex(feedId)
        assertEquals(70, index.size)
        assertEquals(70, FeedSearch.rank(index, "astronmy").size)
        val last = store.library.value.episodes.single { it.feedId == feedId && it.title == "Episode 70" }
        val revision = store.library.value.catalogueRevision
        store.saveFeed("https://example.com/many", FeedParser.Feed("Many", listOf(
            FeedParser.Item("search-70", "Episode 70", "https://example.com/70.mp3", "botany", 70, 0))), feedId)
        assertTrue(store.library.value.catalogueRevision > revision)
        val updated = store.feedSearchIndex(feedId)
        assertEquals(70, updated.size)
        assertEquals(listOf(last.id), FeedSearch.rank(updated, "botany"))
        assertEquals(69, FeedSearch.rank(updated, "astronomy").size)
    }
    @Test fun activeTrackSurvivesLaunchCleanup() = runBlocking {
        store.activate(episode.id)
        store.expireAtLaunch(100)
        assertTrue(file.exists())
    }
    @Test fun deletionPreservesBookmarksRatingsAndPosition() = runBlocking {
        store.rate(episode.id, 5)
        store.bookmark(episode.id, 4000, "Remember")
        store.progress(episode.id, 5000, 10000)
        store.deleteAudio(episode.id)
        val retained = store.library.value.episodes.single()
        assertEquals(5, retained.stars)
        assertEquals(5000, retained.position)
        assertEquals("Remember", store.library.value.bookmarks.single().name)
        assertNull(retained.path)
    }
    @Test fun heardThresholdAndUnheardRemoval() = runBlocking {
        store.progress(episode.id, 9499, 10000)
        assertFalse(store.library.value.episodes.single().heard)
        store.progress(episode.id, 9500, 10000)
        assertTrue(store.library.value.episodes.single().heard)
        assertEquals(2L, store.library.value.episodes.single().expiry)
        store.membership(episode.id, "unheard", true)
        assertFalse(store.library.value.episodes.single().heard)
        assertEquals(0, store.library.value.episodes.single().position)
        store.membership(episode.id, "unheard", false)
        assertTrue(store.library.value.episodes.single().heard)
    }
    @Test fun refreshPreservesPersonalStateAndCustomFeedName() = runBlocking {
        store.rate(episode.id, 4)
        store.editFeed(episode.feedId, "My name", null, setOf("4ever"))
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Publisher name", listOf(
            FeedParser.Item("guid", "Updated title", "https://example.com/new.mp3", "New notes", 200, 20_000))), episode.feedId)
        assertEquals(1, store.library.value.episodes.size)
        assertEquals(4, store.library.value.episodes.single().stars)
        assertEquals("Updated title", store.library.value.episodes.single().title)
        assertEquals("My name", store.library.value.feeds.single().name)
        assertTrue(store.library.value.episodes.single().isDownloaded)
    }

    @Test fun latestRefreshHighlightsOnlyNewIdentitiesAndSurvivesReopening() = runBlocking {
        val url = "https://example.com/feed"
        val updated = FeedParser.Item("guid", "Edited old episode", "https://example.com/audio.mp3", "Changed", 999, 10_000)
        // A late-discovered episode is new even if its publication date is old.
        val added = FeedParser.Item("new", "New arrival", "https://example.com/new.mp3", "", 1, 10_000)
        assertTrue(store.library.value.episode(episode.id)!!.newInLastRefresh)
        store.saveFeed(url, FeedParser.Feed("Feed", listOf(updated, added, added)), episode.feedId)
        assertFalse(store.library.value.episode(episode.id)!!.newInLastRefresh)
        val newEpisode = store.library.value.episodes.single { it.title == "New arrival" }
        assertTrue(newEpisode.newInLastRefresh)
        store.progress(newEpisode.id, 500, 10_000)
        store.close()
        store = LibraryStore(RuntimeEnvironment.getApplication())
        store.initialize()
        assertTrue(store.library.value.episode(newEpisode.id)!!.newInLastRefresh)
        store.saveFeed(url, FeedParser.Feed("Feed", listOf(updated)), episode.feedId)
        assertEquals(2, store.library.value.episodes.size) // Retained history also loses its old highlight.
        assertTrue(store.library.value.episodes.none { it.newInLastRefresh })
    }

    @Test fun refreshMarkersArePerFeedAndFailuresOrUnsubscribeRacesPreserveThem() = runBlocking {
        val otherUrl = "https://example.com/other"
        val other = FeedParser.Feed("Other", listOf(FeedParser.Item("other", "Other", "https://example.com/other.mp3", "", 1, 0)))
        val otherId = store.saveFeed(otherUrl, other)
        store.feedError(episode.feedId, "Offline")
        assertTrue(store.library.value.episode(episode.id)!!.newInLastRefresh)
        store.saveFeed(otherUrl, other, otherId)
        assertFalse(store.library.value.episodes.single { it.feedId == otherId }.newInLastRefresh)
        assertTrue(store.library.value.episode(episode.id)!!.newInLastRefresh)
        store.unsubscribe(episode.feedId)
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Feed", emptyList()), episode.feedId)
        assertTrue(store.library.value.episode(episode.id)!!.newInLastRefresh)
    }
    @Test fun deletingCustomListKeepsAudioAndClearsFeedDefault() = runBlocking {
        store.createList("My list")
        val list = store.library.value.lists.single { !it.system }
        store.membership(episode.id, list.id, true)
        store.editFeed(episode.feedId, "Feed", 30, setOf(list.id))
        store.deleteList(list.id)
        assertTrue(file.exists())
        assertTrue(store.library.value.feeds.single().defaults.isEmpty())
        assertTrue(store.library.value.episodes.single().lists.isEmpty())
    }
    @Test fun feedArtworkPersistsAndRefreshesWithoutChangingPersonalState() = runBlocking {
        store.rate(episode.id, 5)
        store.editFeed(episode.feedId, "Custom name", null, setOf("4ever"))
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Publisher", emptyList(), "https://example.com/cover.jpg"), episode.feedId)
        store.initialize()
        assertEquals("https://example.com/cover.jpg", store.library.value.feedArtwork(episode.feedId))
        assertEquals("Custom name", store.library.value.feeds.single().name)
        assertEquals(5, store.library.value.episodes.single().stars)
        assertTrue(store.library.value.episodes.single().isDownloaded)
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Publisher", emptyList()), episode.feedId)
        assertNull(store.library.value.feedArtwork(episode.feedId))
    }
    @Test fun unsubscribePreservesDownloadsHistoryProtectionAndQueue() = runBlocking {
        store.rate(episode.id, 5)
        store.bookmark(episode.id, 1000, "Keep")
        store.progress(episode.id, 5000, 10000)
        store.membership(episode.id, "4ever", true)
        store.enqueue(episode.id)
        store.activate(episode.id)
        store.unsubscribe(episode.feedId)
        store.initialize()
        val library = store.library.value
        assertTrue(library.subscriptions.isEmpty())
        assertTrue(library.latestEpisodes.isEmpty())
        assertEquals("Feed", library.feedName(episode.feedId))
        assertFalse(library.feeds.single().subscribed)
        assertEquals(5, library.episodes.single().stars)
        assertEquals(5000L, library.episodes.single().position)
        assertTrue(library.episodes.single().protected)
        assertEquals("Keep", library.bookmarks.single().name)
        assertEquals(listOf(episode.id), library.queue)
        assertEquals(episode.id, store.activeEpisode.get())
        assertTrue(file.exists())
    }
    @Test fun refreshInFlightDoesNotUndoUnsubscribe() = runBlocking {
        store.unsubscribe(episode.feedId)
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Remote", listOf(
            FeedParser.Item("new", "New episode", "https://example.com/new.mp3", "", 0, 0))), episode.feedId)
        assertTrue(store.library.value.subscriptions.isEmpty())
        assertEquals(1, store.library.value.episodes.size)
    }
    @Test fun explicitResubscriptionReusesSavedFeedAndEpisodes() = runBlocking {
        store.editFeed(episode.feedId, "Custom name", null, setOf("4ever"))
        store.rate(episode.id, 4)
        store.unsubscribe(episode.feedId)
        val id = store.saveFeed("https://example.com/feed", FeedParser.Feed("Remote", listOf(
            FeedParser.Item("guid", "Updated episode", "https://example.com/audio.mp3", "Info", 100, 10000))))
        assertEquals(episode.feedId, id)
        assertEquals(1, store.library.value.feeds.size)
        assertEquals("Custom name", store.library.value.subscriptions.single().name)
        assertEquals(setOf("4ever"), store.library.value.subscriptions.single().defaults)
        assertEquals(episode.id, store.library.value.latestEpisodes.single().id)
        assertEquals(4, store.library.value.episodes.single().stars)
        assertTrue(file.exists())
    }
    @Test fun delayedProgressCannotUndoExplicitUnheardReset() = runBlocking {
        store.progress(episode.id, 9600, 10000)
        val oldEpoch = store.progressEpoch(episode.id)
        store.membership(episode.id, "unheard", true)
        store.progress(episode.id, 9600, 10000, oldEpoch)
        val reset = store.library.value.episode(episode.id)!!
        assertFalse(reset.heard); assertEquals(0L, reset.position)
        store.progress(episode.id, 9600, 10000, store.progressEpoch(episode.id))
        assertTrue(store.library.value.episode(episode.id)!!.heard)
    }
    @Test fun measuredDurationAndLightweightSnapshots() = runBlocking {
        val otherFeed = store.saveFeed("https://example.com/other", FeedParser.Feed("Other", listOf(
            FeedParser.Item("other", "Other", "https://example.com/other.mp3", "Notes".repeat(100_000), 0, 100))))
        val other = store.library.value.episodes.single { it.feedId == otherFeed }
        val revision = store.library.value.catalogueRevision
        store.progress(episode.id, 9600, 20000)
        val current = store.library.value.episode(episode.id)!!
        assertFalse(current.heard); assertEquals(20000L, current.duration)
        assertSame(other, store.library.value.episode(other.id))
        assertEquals(revision, store.library.value.catalogueRevision)
        assertEquals("", other.description)
        assertEquals(500_000, store.description(other.id).length)
    }
    @Test fun noOpReconciliationKeepsSameSnapshot() = runBlocking {
        val before = store.library.value
        store.reconcileDownloads()
        assertSame(before, store.library.value)
    }
    @Test fun unchangedRefreshDoesNotRewriteEpisodeNotes() = runBlocking {
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE audit_updates(count INTEGER)")
        db.execSQL("INSERT INTO audit_updates VALUES(0)")
        db.execSQL("CREATE TRIGGER count_updates AFTER UPDATE OF title,audio_url,description,published ON episodes BEGIN UPDATE audit_updates SET count=count+1; END")
        store.saveFeed("https://example.com/feed", FeedParser.Feed("Feed", listOf(
            FeedParser.Item("guid", "Episode", "https://example.com/audio.mp3", "Info", 100, 10_000))), episode.feedId)
        db.rawQuery("SELECT count FROM audit_updates", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    @Test fun backupRoundTripMergesWithoutLosingAudioAndDeduplicates() = runBlocking {
        store.rate(episode.id, 5); store.progress(episode.id, 4500, 10000)
        store.bookmark(episode.id, 1234, "Moment"); store.membership(episode.id, "4ever", true)
        store.createList("Personal")
        val list = store.library.value.lists.single { !it.system }
        store.membership(episode.id, list.id, true)
        store.editFeed(episode.feedId, "Custom feed", 90, setOf(list.id))
        store.enqueue(episode.id)
        val output = java.io.ByteArrayOutputStream()
        store.exportLibrary(output)
        val backup = output.toString("UTF-8")
        assertFalse(backup.contains("manager_id")); assertFalse(backup.contains(file.absolutePath))
        assertFalse(backup.contains("new_in_last_refresh"))
        store.rate(episode.id, 1); store.progress(episode.id, 10, 10000)
        repeat(2) { store.restoreLibrary(backup.byteInputStream()) }
        val after = store.library.value.episode(episode.id)!!
        assertEquals(5, after.stars); assertEquals(4500L, after.position); assertEquals(file.absolutePath, after.path)
        assertTrue(file.exists()); assertEquals(1, store.library.value.bookmarks.size)
        assertEquals(listOf(episode.id), store.library.value.queue)
        assertTrue(after.protected); assertTrue(list.id in after.lists)
        assertEquals("Custom feed", store.library.value.feedName(episode.feedId))
    }
    @Test fun invalidBackupRollsBackEveryChange() = runBlocking {
        val output = java.io.ByteArrayOutputStream(); store.exportLibrary(output)
        val invalid = output.toString("UTF-8").replace("\"Feed\"", "\"Changed\"").replace("\"stars\":0", "\"stars\":9")
        try { store.restoreLibrary(invalid.byteInputStream()); fail("Invalid rating must fail") } catch (_: IllegalArgumentException) { }
        store.initialize()
        assertEquals("Feed", store.library.value.feedName(episode.feedId))
        assertTrue(file.exists())
    }
    @Test fun largeLibraryProgressKeepsOtherObjectsAndNotesOutOfState() = runBlocking {
        val many = (1..10_000).map { FeedParser.Item("g$it", "Episode $it", "https://example.com/$it.mp3", "notes".repeat(400), it.toLong(), 10000) }
        store.saveFeed("https://example.com/large", FeedParser.Feed("Large", many))
        val before = store.library.value
        val started = System.nanoTime()
        store.progress(episode.id, 2000, 10000)
        val after = store.library.value
        assertEquals(10001, after.episodes.size)
        assertTrue(after.episodes.all { it.description.isEmpty() })
        assertSame(before.episodes.last(), after.episodes.last())
        assertEquals(before.catalogueRevision, after.catalogueRevision)
        println("10,001 episodes, 20 MB notes: progress save " + (System.nanoTime() - started) / 1_000_000 + " ms")
    }

    @Test fun completedDownloadRetainsManagerIdUntilAudioDeletion() = runBlocking {
        store.deleteAudio(episode.id)
        store.download(episode.id)
        val id = store.library.value.episode(episode.id)!!.downloadId!!
        val manager = RuntimeEnvironment.getApplication().getSystemService(DownloadManager::class.java)
        val downloads = shadowOf(manager)
        shadowOf(downloads.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.writeBytes(byteArrayOf(1, 2, 3))
        store.reconcileDownloads()
        val completed = store.library.value.episode(episode.id)!!
        assertNull(completed.downloadId); assertEquals(id, completed.managerId)
        assertEquals(1, downloads.requestCount); assertTrue(file.exists())
        val snapshot = store.library.value
        store.reconcileDownloads(); assertSame(snapshot, store.library.value)
        store.deleteAudio(episode.id)
        assertFalse(file.exists()); assertEquals(0, downloads.requestCount)
        assertNull(store.library.value.episode(episode.id)!!.managerId)
    }
    @Test fun canceledRemovedAndFailedDownloadsCanBeRetried() = runBlocking {
        store.deleteAudio(episode.id)
        val manager = RuntimeEnvironment.getApplication().getSystemService(DownloadManager::class.java)
        val downloads = shadowOf(manager)
        store.download(episode.id); store.cancelDownload(episode.id)
        assertEquals(0, downloads.requestCount); assertNull(store.library.value.episode(episode.id)!!.downloadId)
        store.download(episode.id)
        manager.remove(store.library.value.episode(episode.id)!!.downloadId!!)
        store.reconcileDownloads()
        assertTrue(store.library.value.episode(episode.id)!!.error!!.contains("removed"))
        store.download(episode.id)
        shadowOf(downloads.getRequest(store.library.value.episode(episode.id)!!.downloadId!!)).setStatus(DownloadManager.STATUS_FAILED)
        store.reconcileDownloads()
        assertNull(store.library.value.episode(episode.id)!!.downloadId)
        assertNotNull(store.library.value.episode(episode.id)!!.error)
        assertEquals(0, downloads.requestCount)
    }
    @Test fun restoreOnEmptyLibraryRemapsReferencesAndOmitsDeviceState() = runBlocking {
        store.rate(episode.id, 4); store.bookmark(episode.id, 2222, "Keep"); store.enqueue(episode.id)
        store.membership(episode.id, "4ever", true)
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("playback", 0).edit().putString("current", episode.id)
            .putString("order", org.json.JSONArray(listOf(episode.id)).toString()).putFloat("speed", 1.5f).commit()
        val output = java.io.ByteArrayOutputStream(); store.exportLibrary(output)
        helper.writableDatabase.execSQL("DELETE FROM episodes")
        helper.writableDatabase.execSQL("DELETE FROM feeds")
        store.initialize(); store.restoreLibrary(output.toByteArray().inputStream())
        val restored = store.library.value.episodes.single()
        assertNotEquals(episode.id, restored.id); assertEquals(4, restored.stars)
        assertNull(restored.path); assertNull(restored.downloadId); assertNull(restored.managerId)
        assertTrue(restored.protected); assertEquals(restored.id, store.library.value.queue.single())
        assertEquals(restored.id, store.library.value.bookmarks.single().episodeId)
        assertEquals("Info", store.description(restored.id))
        val prefs = context.getSharedPreferences("playback", 0)
        assertEquals(restored.id, prefs.getString("current", null)); assertEquals(1.5f, prefs.getFloat("speed", 1f))
    }

    @Test fun sharedAudioStillWorksAfterPortableRestore() = runBlocking {
        store.addSharedAudio("https://example.com/shared.mp3", "Shared")
        val output = java.io.ByteArrayOutputStream(); store.exportLibrary(output)
        helper.writableDatabase.execSQL("DELETE FROM episodes")
        helper.writableDatabase.execSQL("DELETE FROM feeds")
        store.initialize()
        assertTrue(store.library.value.feeds.isEmpty())
        store.restoreLibrary(output.toByteArray().inputStream())
        assertEquals("shared", store.library.value.feeds.single { it.url.isEmpty() }.id)
        val another = store.addSharedAudio("https://example.com/another.mp3", "Another")
        store.download(another)
        assertNotNull(store.library.value.episode(another)!!.downloadId)
        assertEquals(2, store.library.value.episodes.count { it.feedId == "shared" })
    }
    @Test fun restoreRejectsDeviceSpecificFieldsAndMissingReferences() = runBlocking {
        val output = java.io.ByteArrayOutputStream(); store.exportLibrary(output)
        val json = org.json.JSONObject(output.toString("UTF-8"))
        json.getJSONArray("episodes").getJSONObject(0).put("path", "/other/file")
        try { store.restoreLibrary(json.toString().byteInputStream()); fail("Paths must not be restored") } catch (_: IllegalArgumentException) { }
        json.getJSONArray("episodes").getJSONObject(0).remove("path")
        json.getJSONArray("episodes").getJSONObject(0).put("feed_id", "missing")
        try { store.restoreLibrary(json.toString().byteInputStream()); fail("Orphans must not be restored") } catch (_: IllegalArgumentException) { }
        assertTrue(file.exists()); assertEquals(1, store.library.value.episodes.size)
    }

}
