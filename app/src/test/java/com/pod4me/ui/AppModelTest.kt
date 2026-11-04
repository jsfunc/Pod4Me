package com.pod4me.ui

import android.net.Uri
import com.pod4me.PodApplication
import com.pod4me.domain.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PodApplication::class)
class AppModelTest {
    @Test fun simultaneousLargeDownloadPromptsAreQueuedAndDeduplicated() {
        val model = AppModel(RuntimeEnvironment.getApplication())
        val first = com.pod4me.domain.Episode("one", "feed", "One", "https://example.com/one.mp3", "", 0)
        val second = first.copy(id = "two", title = "Two")
        val enqueue = AppModel::class.java.getDeclaredMethod("queueDownloadConfirmation", com.pod4me.domain.Episode::class.java).apply { isAccessible = true }
        enqueue.invoke(model, first); enqueue.invoke(model, second)
        enqueue.invoke(model, first); enqueue.invoke(model, second)
        assertEquals(first, model.downloadConfirmation.value)
        model.dismissDownloadConfirmation()
        assertEquals(second, model.downloadConfirmation.value)
        model.dismissDownloadConfirmation()
        assertNull(model.downloadConfirmation.value)
        model.player.disconnect()
    }

    @Test fun statusAndInputHelpersKeepFailuresVisibleAndTimersAccurate() {
        assertEquals(listOf("a", "c"), AppModel.remainingRefreshFailures(listOf("a", "b"), listOf("b", "c"), listOf("c"), listOf("a", "b", "c")))
        assertEquals("https://feeds.example.com/show.xml", AppModel.subscriptionUrl("feeds.example.com/show.xml"))
        assertEquals("https://podcasts.apple.com/fr/podcast/id123", AppModel.subscriptionUrl("podcasts.apple.com/fr/podcast/id123"))
        assertNull(AppModel.subscriptionUrl("Culture / history"))
        assertEquals(30_000L, sleepTimerRemaining(com.pod4me.playback.PlaybackUi(duration = 90_000, position = 30_000, speed = 2f, sleepEnd = true)))
        for (status in listOf("RECONNECTING", "UNAVAILABLE", "PAUSE_LIMIT")) {
            assertNotEquals("Expired position", radioStatus(com.pod4me.playback.PlaybackUi(radioStatus = status, radioNotice = "Expired position")))
        }
    }

    @Test fun importPreviewNormalizesChannelsAndCountsWithoutChangingLibrary() {
        val preview = AppModel.previewImport(listOf(
            com.pod4me.domain.SubscriptionFormat.Entry("https://example.com/existing", "New name"),
            com.pod4me.domain.SubscriptionFormat.Entry("https://example.com/new", "New"),
            com.pod4me.domain.SubscriptionFormat.Entry("https://example.com/new", "Duplicate"),
            com.pod4me.domain.SubscriptionFormat.Entry("file:///private/feed", "Invalid"),
            com.pod4me.domain.SubscriptionFormat.Entry("https://www.youtube.com/@Channel/videos", "Channel")
        ), setOf("https://example.com/existing"))
        assertEquals(2, preview.entries.size); assertEquals(1, preview.already)
        assertEquals(1, preview.invalid); assertEquals(1, preview.duplicates)
        assertEquals("New", preview.entries.first().title())
        assertEquals("https://www.youtube.com/@channel", preview.entries.last().url())
    }

    @Test fun rotationSkipsMaintenanceButReturningFromBackgroundExpiresAudio() = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val model = AppModel(app)
        model.onLaunch()
        val ep = app.store.library.value.episodes.first()
        val file = java.io.File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS), "${ep.id}.audio")
        file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
        com.pod4me.data.LibraryDatabase(app).use {
            it.writableDatabase.execSQL("UPDATE episodes SET path=?,expiry=1 WHERE id=?", arrayOf(file.path, ep.id))
        }
        app.store.initialize()
        val revision = app.store.library.value.catalogueRevision
        model.onLaunch() // Same retained model, as on Activity recreation.
        assertTrue(file.exists())
        assertEquals(revision, app.store.library.value.catalogueRevision)
        model.needsForegroundMaintenance = true
        model.onLaunch()
        assertFalse(file.exists())
    }

    @Test fun busyActionsGiveFeedbackAndKeepIncomingSuggestion() {
        val model = AppModel(RuntimeEnvironment.getApplication())
        val field = AppModel::class.java.getDeclaredField("mutableBusy").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<Boolean>).value = true
        model.importFeeds(Uri.parse("content://test/import"))
        assertTrue(model.notice.value!!.contains("Please wait"))
        model.clearNotice()
        model.receive("pod4me://episode?url=https%3A%2F%2Fexample.com%2Fa.mp3")
        val suggestion = model.suggestion.value!!
        model.acceptSuggestion(suggestion, "episode", false)
        assertEquals(suggestion, model.suggestion.value)
        assertTrue(model.notice.value!!.contains("Please wait"))
        model.player.disconnect()
    }
}
