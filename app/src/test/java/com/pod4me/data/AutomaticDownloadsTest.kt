package com.pod4me.data

import android.app.Application
import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Environment
import com.pod4me.domain.FeedParser
import com.pod4me.domain.Policy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutomaticDownloadsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    private lateinit var store: LibraryStore
    private lateinit var feedId: String
    private fun item(id: String, published: Long, size: Long? = null) =
        FeedParser.Item(id, id, "https://example.com/$id.mp3", "", published, 60_000, false, size)
    private fun feed() = store.library.value.feeds.single { it.id == feedId }
    private fun episode(title: String) = store.library.value.episodes.single { it.title == title }
    private suspend fun refresh(vararg items: FeedParser.Item) {
        store.applyRefresh(feed(), FeedResponse(FeedParser.Feed("Feed", items.toList()), "tag", null))
    }
    private fun mobile() {
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true, true))
    }
    @Before fun setup() = runBlocking {
        context.deleteDatabase("pod4me.db")
        store = LibraryStore(context)
        store.initialize()
        feedId = store.saveFeed("https://example.com/feed", FeedParser.Feed("Feed", listOf(item("known", 2000))))
    }
    @After fun close() { store.close() }

    @Test fun onlyNewPublishedEpisodesAreQueuedAndFutureOnesWaitThrough304() = runBlocking {
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 1000)
        refresh(item("known", 2000), item("archive", 999), item("undated", 0), item("new", 1500), item("future", 5000))
        assertTrue(store.startAutoDownloads(feedId, now = 3000).isEmpty())
        assertNotNull(episode("new").downloadId)
        for (title in listOf("known", "archive", "undated", "future")) assertNull(episode(title).downloadId)
        assertEquals(1, shadowOf(manager).requestCount)
        store.applyRefresh(feed(), FeedResponse(null, "tag", null))
        store.startAutoDownloads(feedId, now = 6000)
        assertNotNull(episode("future").downloadId)
        store.startAutoDownloads(feedId, now = 7000)
        assertEquals(2, shadowOf(manager).requestCount)
    }

    @Test fun wifiOnlyRequestCannotUseMobileEvenForALargeFile() = runBlocking {
        mobile()
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 1000)
        refresh(item("large", 1500, 300_000_000))
        assertTrue(store.startAutoDownloads(feedId, now = 2000).isEmpty())
        val request = shadowOf(shadowOf(manager).getRequest(episode("large").downloadId!!))
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request.allowedNetworkTypes)
        assertFalse(request.allowedOverMetered); assertFalse(request.allowedOverRoaming)
    }

    @Test fun aPublisherDateCorrectionCannotTurnAnOldOrUndatedEpisodeIntoAnAutomaticDownload() = runBlocking {
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 1000)
        refresh(item("corrected", 5000))
        refresh(item("corrected", 500))
        store.startAutoDownloads(feedId, now = 2000)
        assertNull(episode("corrected").downloadId)
        refresh(item("corrected", 0))
        store.startAutoDownloads(feedId, now = 2000)
        assertNull(episode("corrected").downloadId)
        refresh(item("corrected", 1500))
        store.startAutoDownloads(feedId, now = 2000)
        assertNotNull(episode("corrected").downloadId)
        assertEquals(1, shadowOf(manager).requestCount)
    }

    @Test fun futureCandidateSurvivesRestartAndTurningOffThenOnDoesNotBackfill() = runBlocking {
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 1000)
        refresh(item("future", 5000))
        LibraryStore(context).use { reopened ->
            reopened.initialize()
            assertEquals(1, reopened.library.value.feeds.single().autoDownload)
            reopened.startAutoDownloads(feedId, now = 4000)
            assertEquals(0, shadowOf(manager).requestCount)
            reopened.applyRefresh(reopened.library.value.feeds.single(), FeedResponse(null, "tag", null))
            reopened.startAutoDownloads(feedId, now = 6000)
            assertNotNull(reopened.library.value.episodes.single { it.title == "future" }.downloadId)
        }
        store.initialize()
        store.cancelDownload(episode("future").id)
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 0, now = 6000)
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 7000)
        refresh(item("future", 5000), item("archive-after-enable", 6500), item("new-after-enable", 8000))
        store.startAutoDownloads(feedId, now = 9000)
        assertNull(episode("future").downloadId)
        assertNull(episode("archive-after-enable").downloadId)
        assertNotNull(episode("new-after-enable").downloadId)
    }

    @Test fun mobilePolicyQueuesSmallFilesButRequiresExplicitLargeFileApproval() = runBlocking {
        mobile()
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 2, now = 1000)
        refresh(item("small", 1500, 200_000_000), item("large", 1500, 200_000_001), item("large2", 1500, 300_000_000))
        val approvals = store.startAutoDownloads(feedId, now = 2000)
        assertEquals(setOf("large", "large2"), approvals.map { it.title }.toSet())
        assertNull(episode("large").downloadId); assertTrue(episode("large").error!!.contains("approval"))
        val small = shadowOf(shadowOf(manager).getRequest(episode("small").downloadId!!))
        assertTrue(small.allowedOverMetered)
        assertTrue(store.startAutoDownloads(feedId, now = 3000).isEmpty())
        store.download(episode("large").id, allowLargeMetered = true)
        assertNotNull(episode("large").downloadId)
        assertNull(episode("large2").downloadId)
    }

    @Test fun disabledAndUnsubscribedFeedsDoNotStartDeferredDownloads() = runBlocking {
        refresh(item("off", 1500))
        assertTrue(store.startAutoDownloads(feedId, now = 2000).isEmpty())
        assertEquals(0, shadowOf(manager).requestCount)
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 1000)
        refresh(item("deferred", 1500))
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 0, now = 2000)
        store.startAutoDownloads(feedId, now = 3000)
        assertNull(episode("deferred").downloadId)
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 3000)
        refresh(item("next", 4000))
        store.unsubscribe(feedId)
        store.startAutoDownloads(feedId, now = 5000)
        assertNull(episode("next").downloadId)
        assertEquals(0, shadowOf(manager).requestCount)
    }

    @Test fun completionUsesFeedExpiryListsAndActualFileSizeWithoutAutomaticRedownload() = runBlocking {
        store.createList("Information")
        val listId = store.library.value.lists.single { it.name == "Information" }.id
        store.editFeed(feedId, "Feed", 5, setOf(listId), autoDownload = 1, now = 1000)
        refresh(item("new", 1500, 999_999))
        store.startAutoDownloads(feedId, now = 2000)
        val ep = episode("new")
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${ep.id}.audio")
        file.writeBytes(ByteArray(1234) { 1 })
        shadowOf(shadowOf(manager).getRequest(ep.downloadId!!)).setStatus(DownloadManager.STATUS_SUCCESSFUL)
        store.reconcileDownloads()
        val complete = episode("new")
        assertEquals(1234L, complete.downloadedSize)
        assertEquals(complete.downloaded!! + 5 * Policy.DAY, complete.expiry)
        assertTrue(listId in complete.lists); assertFalse(complete.heard); assertEquals(0L, complete.position)
        refresh(item("new", 1500, 888_888))
        assertEquals(1234L, episode("new").downloadedSize)
        store.deleteAudio(ep.id)
        assertNull(episode("new").downloadedSize)
        refresh(item("new", 1500)); store.startAutoDownloads(feedId, now = 3000)
        assertNull(episode("new").downloadId)
    }

    @Test fun cancellationAndFailureStayManualOnLaterRefresh() = runBlocking {
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 1, now = 1000)
        refresh(item("cancel", 1500), item("fail", 1500))
        store.startAutoDownloads(feedId, now = 2000)
        store.cancelDownload(episode("cancel").id)
        shadowOf(shadowOf(manager).getRequest(episode("fail").downloadId!!)).setStatus(DownloadManager.STATUS_FAILED)
        store.reconcileDownloads()
        refresh(item("cancel", 1500), item("fail", 1500))
        store.startAutoDownloads(feedId, now = 3000)
        assertNull(episode("cancel").downloadId); assertNull(episode("fail").downloadId)
        assertNotNull(episode("fail").error)
        assertEquals(0, shadowOf(manager).requestCount)
    }

    @Test fun existingDownloadsGainTheirActualSizeDuringMaintenanceAndMissingFilesClearIt() = runBlocking {
        val ep = episode("known")
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${ep.id}.audio")
        file.parentFile!!.mkdirs(); file.writeBytes(ByteArray(456) { 1 })
        LibraryDatabase(context).use { it.writableDatabase.execSQL("UPDATE episodes SET path=?,downloaded=123 WHERE id=?", arrayOf(file.path, ep.id)) }
        store.reconcileDownloads()
        assertEquals(456L, episode("known").downloadedSize)
        file.delete(); store.reconcileDownloads()
        assertFalse(episode("known").isDownloaded); assertNull(episode("known").downloadedSize)
    }

    @Test fun backupRestoresPolicyButDoesNotDownloadRestoredHistoryAndAcceptsVersionTwo() = runBlocking {
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 2, now = 1000)
        val output = java.io.ByteArrayOutputStream(); store.exportLibrary(output)
        val json = org.json.JSONObject(output.toString("UTF-8"))
        assertEquals(3, json.getInt("version"))
        store.editFeed(feedId, "Feed", 5, emptySet(), autoDownload = 0)
        store.restoreLibrary(output.toByteArray().inputStream())
        assertEquals(2, feed().autoDownload)
        refresh(item("restored-archive", 1500)); store.startAutoDownloads(feedId)
        assertEquals(0, shadowOf(manager).requestCount)
        json.put("version", 2)
        val feeds = json.getJSONArray("feeds")
        for (index in 0 until feeds.length()) feeds.getJSONObject(index).remove("auto_download")
        store.restoreLibrary(json.toString().byteInputStream())
        assertEquals(0, feed().autoDownload)
    }
}
