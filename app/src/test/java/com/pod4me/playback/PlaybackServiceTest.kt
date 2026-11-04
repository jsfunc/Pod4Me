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
    private fun service(app: PodApplication) = Robolectric.buildService(PlaybackService::class.java).create().also { controller ->
        controller.get().createRadioBuffer = { station ->
            RadioBuffer(app.cacheDir, station.url, capture = false).also { buffer ->
                val audio = javaClass.getResourceAsStream("/radio/silence.mp3")!!.use { it.readBytes() }
                repeat(12) { buffer.window.append(audio, 2_100_000, "mp3") }
            }
        }
    }

    @Test fun bufferedRadioExposesSeekableTimelineAndPauseDoesNotReconnect() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val controller = service(app)
        try {
            controller.get().startRadio(app.radios.read().first().id, play = false)
            val p = player(controller.get())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!p.isCurrentMediaItemSeekable && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue(p.isCurrentMediaItemSeekable)
            assertEquals(25200L, p.duration)
            val uri = p.currentMediaItem!!.localConfiguration!!.uri
            p.seekTo(20000)
            p.seekBack()
            assertEquals(10000L, p.currentPosition)
            controller.get().togglePlayback()
            assertEquals(uri, p.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(10000L, p.currentPosition)
            controller.get().togglePlayback()
            assertFalse(p.playWhenReady)
            assertEquals(uri, p.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(1f, p.playbackParameters.speed)
        } finally { controller.destroy() }
    }

    @Test fun videoStreamingKeepsQueueAndProgressAndDownloadedVideoUsesLocalFile() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val audio = queuedEpisodes(app)
        val feed = app.store.saveFeed("https://example.com/videos", FeedParser.Feed("Videos", listOf(
            FeedParser.Item("v", "Video", "https://example.com/episode.mp4", "", 0, 10000, true))))
        val video = app.store.library.value.episodes.single { it.feedId == feed }
        app.store.progress(video.id, 2000, 10000)
        val service = service(app)
        try {
            service.get().startEpisode(audio.first().id, audio.map { it.id }, 1000, play = false)
            player(service.get()).setPlaybackSpeed(1.5f)
            val queue = app.store.library.value.queue
            service.get().startVideoStream(video.id, play = false)
            assertEquals(video.audioUrl, player(service.get()).currentMediaItem!!.localConfiguration!!.uri.toString())
            assertEquals(1, player(service.get()).mediaItemCount)
            assertEquals(2000L, player(service.get()).currentPosition)
            assertEquals(1.5f, player(service.get()).playbackParameters.speed)
            assertEquals(queue, app.store.library.value.queue)
            assertEquals(video.id, app.store.activeEpisode.get())
            service.get().stopPlayback()
            assertEquals(audio.first().id, app.getSharedPreferences("playback", 0).getString("current", null))
            val local = File(app.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${video.id}.audio")
            local.writeBytes(byteArrayOf(1, 2, 3))
            LibraryDatabase(app).use { it.writableDatabase.execSQL("UPDATE episodes SET path=? WHERE id=?", arrayOf(local.path, video.id)) }
            app.store.initialize()
            service.get().startEpisode(video.id, listOf(video.id), -1, play = false)
            assertEquals("file", player(service.get()).currentMediaItem!!.localConfiguration!!.uri.scheme)
            service.get().startRadio(app.radios.read().first().id, play = false)
            assertEquals(1f, player(service.get()).playbackParameters.speed)
            service.get().startVideoStream(video.id, play = false)
            service.get().handlePlaybackError()
            assertNull(app.store.activeEpisode.get())
            assertEquals(0, player(service.get()).mediaItemCount)
            assertTrue(app.error.value!!.contains("video stream"))
        } finally { service.destroy() }
    }

    @Test fun radioDoesNotConsumeQueueOrOverwritePodcastSpeedAndResume() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        val service = service(app)
        try {
            val episode = episodes.first()
            service.get().startEpisode(episode.id, episodes.map { it.id }, 2000, play = false)
            player(service.get()).setPlaybackSpeed(1.75f)
            val queue = app.store.library.value.queue
            val station = app.radios.read().first()
            service.get().startRadio(station.id, play = false)
            assertEquals(station.id, player(service.get()).currentMediaItem!!.mediaId)
            assertEquals(1, player(service.get()).mediaItemCount)
            assertEquals(1f, player(service.get()).playbackParameters.speed)
            assertNull(app.store.activeEpisode.get())
            assertEquals(queue, app.store.library.value.queue)
            assertEquals(2000L, app.store.library.value.episode(episode.id)!!.position)
            assertFalse(service.get().playerCommands().contains(Player.COMMAND_SET_SPEED_AND_PITCH))
            assertTrue(service.get().playerCommands().contains(Player.COMMAND_SEEK_FORWARD))
            assertEquals(station, service.get().savedRadio())
            service.get().startEpisode(episode.id, episodes.map { it.id }, -1, play = false)
            assertEquals(1.75f, player(service.get()).playbackParameters.speed)
            assertEquals(2000L, player(service.get()).currentPosition)
            assertEquals(episode.id, app.store.activeEpisode.get())
            assertTrue(service.get().playerCommands().contains(Player.COMMAND_SEEK_FORWARD))
            assertNull(service.get().savedRadio())
        } finally { service.destroy() }
    }

    @Test fun stopRadioUnloadsStreamAndKeepsPodcastResume() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        app.store.initialize()
        val prefs = app.getSharedPreferences("playback", 0)
        prefs.edit().putString("current", "last-podcast").commit()
        val service = service(app)
        try {
            service.get().startRadio(app.radios.read().first().id, play = false)
            service.get().stopPlayback()
            assertEquals(0, player(service.get()).mediaItemCount)
            assertNull(service.get().savedRadio())
            assertEquals("last-podcast", prefs.getString("current", null))
        } finally { service.destroy() }
    }

    @Test fun unavailableOrRemovedRadioCannotStartAndErrorsDoNotTouchEpisodes() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        val station = app.radios.read().first()
        val service = service(app)
        try {
            service.get().startRadio(station.id, play = false)
            service.get().handlePlaybackError()
            assertEquals(0, player(service.get()).mediaItemCount)
            assertNull(app.store.activeEpisode.get())
            assertTrue(app.error.value!!.contains(station.name))
            assertEquals(episodes.map { it.id }, app.store.library.value.queue)
            assertTrue(app.store.library.value.episodes.none { it.heard })
            app.radios.unsubscribe(station.id)
            assertNull(service.get().savedRadio())
            try { service.get().startRadio(station.id, play = false); fail("Removed station") }
            catch (_: IllegalArgumentException) {}
        } finally { service.destroy() }
    }

    @Test fun coldSessionDoesNotConnectToSavedRadioAutomatically() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val station = app.radios.read().first()
        app.getSharedPreferences("playback", 0).edit().putString("radio", station.id).commit()
        val service = service(app)
        try {
            assertEquals(0, player(service.get()).mediaItemCount)
            assertEquals(station, service.get().savedRadio())
            assertNull(app.store.activeEpisode.get())
        } finally { service.destroy() }
    }

    @Test fun queueStartSkipsMissingAudioAndKeepsItAvailableForDownload() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        File(episodes[0].path!!).delete()
        val service = service(app)
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
        val service = service(app)
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
        val service = service(app)
        try {
            try { service.get().startQueue(play = false); fail("Missing audio cannot start") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("download an episode")) }
            assertEquals(episodes.map { it.id }, app.store.library.value.queue)
            assertEquals(0, player(service.get()).mediaItemCount)
            assertNull(app.store.activeEpisode.get())
        } finally { service.destroy() }
    }

    @Test fun failedPlaybackUnloadsAndAllowsDeletionButPausedPlaybackIsProtected() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val episodes = queuedEpisodes(app)
        val service = service(app)
        try {
            val ep = episodes.first()
            service.get().startEpisode(ep.id, episodes.map { it.id }, 1000, play = false)
            assertEquals(ep.id, app.store.activeEpisode.get())
            try { app.store.deleteAudio(ep.id); fail("Paused current audio must be protected") }
            catch (_: IllegalArgumentException) {}
            service.get().handlePlaybackError()
            assertNull(app.store.activeEpisode.get())
            assertEquals(0, player(service.get()).mediaItemCount)
            assertFalse(player(service.get()).playWhenReady)
            app.store.deleteAudio(ep.id)
            assertFalse(File(ep.path!!).exists())
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
        val service = service(app)
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
        val service = service(app)
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
