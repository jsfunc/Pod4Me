package com.pod4me.data

import android.app.DownloadManager.*
import org.junit.Assert.*
import org.junit.Test

class DownloadStatusTest {
    @Test fun runningReportsBytesEvenWithoutAKnownLength() {
        assertEquals("Downloading · 25% · 1.0 MB", downloadStatus(STATUS_RUNNING, 0, 1_000_000, 4_000_000))
        assertEquals("Downloading · 1.0 MB", downloadStatus(STATUS_RUNNING, 0, 1_000_000, -1))
        assertEquals("Downloading · 0.0 MB", downloadStatus(STATUS_RUNNING, 0, -1, 0))
        assertEquals("Downloading · 100% · 5.0 MB", downloadStatus(STATUS_RUNNING, 0, 5_000_000, 4_000_000))
    }
    @Test fun queuedAndPausedStatesDoNotPretendToBeDownloading() {
        assertEquals("Queued by Android", downloadStatus(STATUS_PENDING, 0, 0, -1))
        assertEquals("Waiting for Wi-Fi", downloadStatus(STATUS_PAUSED, PAUSED_QUEUED_FOR_WIFI, 0, 1_000_000))
        assertEquals("Waiting for a usable network", downloadStatus(STATUS_PAUSED, PAUSED_WAITING_FOR_NETWORK, 0, -1))
        assertEquals("Connection interrupted · Android will retry · 50% · 1.0 MB",
            downloadStatus(STATUS_PAUSED, PAUSED_WAITING_TO_RETRY, 1_000_000, 2_000_000))
        assertEquals("Paused by Android", downloadStatus(STATUS_PAUSED, PAUSED_UNKNOWN, 0, -1))
    }
}
