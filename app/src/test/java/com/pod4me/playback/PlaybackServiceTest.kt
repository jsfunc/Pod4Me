package com.pod4me.playback

import android.os.Environment
import android.os.Looper
import androidx.media3.common.Player
import com.pod4me.PodApplication
import com.pod4me.data.LibraryDatabase
import com.pod4me.domain.FeedParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PodApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackServiceTest {
    @Test fun queueStartSkipsMissingAudioAndKeepsItAvailableForDownload() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        File(episodes[0].path!!).delete()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            assertEquals(0, player(service.get()).mediaItemCount)
            service.get().startQueue(play = false)
            assertEquals(episodes[1].id, player(service.get()).currentMediaItem!!.mediaId)
            assertEquals(listOf(episodes[0].id, episodes[2].id), app.store.library.value.queue)
            assertEquals(episodes[2].id, player(service.get()).getMediaItemAt(player(service.get()).currentMediaItemIndex + 1).mediaId)
        } finally { service.destroy() }
    }

    @Test fun idlePlayStartsQueueEvenWhenSavedEpisodeNoLongerExists() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        app.getSharedPreferences("playback", 0).edit().putString("current", "deleted").commit()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            service.get().togglePlayback()
            assertEquals(episodes.first().id, player(service.get()).currentMediaItem!!.mediaId)
            assertTrue(player(service.get()).playWhenReady)
            assertEquals(episodes.first().id, app.store.activeEpisode.get())
        } finally { service.destroy() }
    }

    @Test fun unavailableQueueExplainsHowToRecoverWithoutRemovingEntries() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        episodes.forEach { File(it.path!!).delete() }
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            try { service.get().startQueue(play = false); fail("Missing audio cannot start") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("download an episode")) }
            assertEquals(episodes.map { it.id }, app.store.library.value.queue)
            assertEquals(0, player(service.get()).mediaItemCount)
            assertNull(app.store.activeEpisode.get())
        } finally { service.destroy() }
    }

    private suspend fun queuedEpisodes(app: PodApplication): List<com.pod4me.domain.Episode> {
        app.store.initialize()
        app.store.saveFeed("https://example.com/queue", FeedParser.Feed("Queue", (1..3).map {
            FeedParser.Item("q$it", "Queued $it", "https://example.com/q$it.mp3", "", it.toLong(), 10000)
        }))
        val episodes = app.store.library.value.episodes.sortedBy { it.published }
        LibraryDatabase(app).use { helper -> episodes.forEach { ep ->
            val file = File(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${ep.id}.audio")
            file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
            helper.writableDatabase.execSQL("UPDATE episodes SET path=? WHERE id=?", arrayOf(file.path, ep.id))
        } }
        app.store.initialize()
        val downloaded = episodes.map { app.store.library.value.episode(it.id)!! }
        downloaded.forEach { app.store.enqueue(it.id) }
        return downloaded
    }

    @Test fun coldSessionLeavesSavedEpisodeUnloadedAndDeletable() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        app.store.initialize()
        app.store.saveFeed("https://example.com/feed", FeedParser.Feed("Feed", listOf(
            FeedParser.Item("one", "One", "https://example.com/a.mp3", "Notes", 1, 10000))))
        val ep = app.store.library.value.episodes.single()
        val file = File(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${ep.id}.audio")
        file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
        LibraryDatabase(app).use { it.writableDatabase.execSQL("UPDATE episodes SET path=?,expiry=1 WHERE id=?", arrayOf(file.path, ep.id)) }
        app.store.initialize()
        app.getSharedPreferences("playback", 0).edit().putString("current", ep.id).commit()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val player = player(service.get())
            assertEquals(0, player.mediaItemCount)
            assertNull(app.store.activeEpisode.get())
            app.store.expireAtLaunch(100)
            assertFalse(file.exists())
        } finally { service.destroy(); app.store.close() }
    }

    @Test fun startingInMiddleSeedsPreviousAndUnheardResetWins() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        app.store.initialize()
        app.store.saveFeed("https://example.com/feed", FeedParser.Feed("Feed", (1..3).map {
            FeedParser.Item("$it", "Episode $it", "https://example.com/$it.mp3", "", it.toLong(), 10000)
        }))
        val episodes = app.store.library.value.episodes.sortedBy { it.published }
        LibraryDatabase(app).use { helper -> episodes.forEach { ep ->
            val file = File(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${ep.id}.audio")
            file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
            helper.writableDatabase.execSQL("UPDATE episodes SET path=? WHERE id=?", arrayOf(file.path, ep.id))
        } }
        app.store.initialize()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val middle = episodes[1]
            service.get().startEpisode(middle.id, episodes.map { it.id }, 9600, play = false)
            val player = player(service.get())
            assertTrue(player.hasPreviousMediaItem())
            assertEquals(episodes[0].id, player.getMediaItemAt(0).mediaId)
            app.store.progress(middle.id, 9600, 10000)
            val epoch = app.store.progressEpoch(middle.id)
            service.get().markUnheard(middle.id)
            shadowOf(Looper.getMainLooper()).idle()
            app.store.progress(middle.id, 9600, 10000, epoch)
            assertEquals(0L, player.currentPosition)
            assertFalse(app.store.library.value.episode(middle.id)!!.heard)
            assertEquals(0L, app.store.library.value.episode(middle.id)!!.position)
        } finally { service.destroy() }
    }

    private fun player(service: PlaybackService): Player = PlaybackService::class.java.getDeclaredField("player").let {
        it.isAccessible = true; it.get(service) as Player
    }
}
