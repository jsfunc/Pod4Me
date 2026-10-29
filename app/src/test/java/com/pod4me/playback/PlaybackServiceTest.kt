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
