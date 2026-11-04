package com.pod4me.data

import android.app.DownloadManager
import java.util.Locale

/** Android owns the transfer; these labels must not imply that queued/paused work is running. */
internal fun downloadStatus(status: Int, reason: Int, downloaded: Long, total: Long): String {
    val state = when (status) {
        DownloadManager.STATUS_PENDING -> "Queued by Android"
        DownloadManager.STATUS_RUNNING -> "Downloading"
        DownloadManager.STATUS_PAUSED -> when (reason) {
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection interrupted · Android will retry"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a usable network"
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            else -> "Paused by Android"
        }
        else -> "Waiting for Android"
    }
    if (downloaded <= 0 && status != DownloadManager.STATUS_RUNNING) return state
    val bytes = downloaded.coerceAtLeast(0)
    val amount = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    return if (total > 0) "$state · ${(bytes.toDouble() / total * 100).coerceIn(0.0, 100.0).toInt()}% · $amount"
    else "$state · $amount"
}
