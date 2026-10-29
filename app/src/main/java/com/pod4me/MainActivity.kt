package com.pod4me

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pod4me.ui.AppModel
import com.pod4me.ui.PodApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent { PodApp(model) }
        val preferences = getPreferences(MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("askedNotifications", false)) {
            preferences.edit().putBoolean("askedNotifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.onLaunch()
                model.player.connect()
                val ticker = launch { while (isActive) { delay(500); model.player.refresh() } }
                try {
                    while (isActive) {
                        delay(20_000)
                        if (model.library.value.episodes.any { it.downloadId != null }) {
                            try { model.store.reconcileDownloads() } catch (e: Exception) { model.app.report(e) }
                        }
                    }
                } finally { ticker.cancel(); model.player.disconnect() }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(model::receive)
            Intent.ACTION_VIEW -> intent.dataString?.let(model::receive)
        }
    }
}
