package com.pod4me

import android.app.Application
import com.pod4me.data.FeedClient
import com.pod4me.data.LibraryStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PodApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store by lazy { LibraryStore(this) }
    val feeds = FeedClient()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    override fun onCreate() {
        super.onCreate()
        scope.launch { try { store.initialize() } catch (e: Exception) { report(e) } }
    }
    fun report(error: Exception) {
        if (error is CancellationException) throw error
        mutableError.value = error.message ?: "The operation could not be completed"
    }
    fun clearError() { mutableError.value = null }
}
