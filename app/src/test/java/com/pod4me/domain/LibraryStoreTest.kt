package com.pod4me.domain

import android.app.Application
import android.os.Environment
import com.pod4me.data.LibraryDatabase
import com.pod4me.data.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
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
}
