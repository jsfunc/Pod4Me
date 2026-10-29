package com.pod4me.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pod4me.PodApplication
import kotlinx.coroutines.launch

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pending = goAsync()
        val app = context.applicationContext as PodApplication
        app.scope.launch {
            try { app.store.reconcileDownloads() }
            catch (e: Exception) { app.report(e) }
            finally { pending.finish() }
        }
    }
}
