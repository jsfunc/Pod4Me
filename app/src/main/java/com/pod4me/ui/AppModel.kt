package com.pod4me.ui

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pod4me.PodApplication
import com.pod4me.domain.*
import com.pod4me.playback.PlayerClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.roundToLong

data class StorageUi(val usedGb: Long = 0, val freeGb: Long = 0)
class AppModel(application: Application) : AndroidViewModel(application) {
    val app = application as PodApplication
    val store = app.store
    val library = store.library
    val player = PlayerClient(app)
    val error = app.error
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableStorage = MutableStateFlow(StorageUi())
    val storage = mutableStorage.asStateFlow()
    private val mutableSuggestion = MutableStateFlow<Suggestion?>(null)
    val suggestion = mutableSuggestion.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) { delay(500); player.refresh() }
        }
        viewModelScope.launch {
            library.collect { updateStorage() }
        }
    }
    fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() }
        catch (e: SQLiteConstraintException) { app.report(IllegalArgumentException("That name is already in use. Choose another name.")) }
        catch (e: Exception) { app.report(e) }
    } }
    suspend fun onLaunch() {
        try { store.initialize(); store.reconcileDownloads(); store.expireAtLaunch(); updateStorage() }
        catch (e: Exception) { app.report(e) }
    }
    private suspend fun updateStorage() = withContext(Dispatchers.IO) {
        val directory = app.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS) ?: app.filesDir
        val bytes = directory.listFiles()?.sumOf(File::length) ?: 0L
        mutableStorage.value = StorageUi((bytes / 1e9).roundToLong(), (StatFs(directory.absolutePath).availableBytes / 1e9).roundToLong())
    }
    fun refresh(feed: Feed? = null) = network {
        val targets = (feed?.let(::listOf) ?: library.value.feeds).filter { it.url.isNotBlank() }
        var successes = 0
        val failures = mutableListOf<String>()
        for (target in targets) {
            try { store.saveFeed(target.url, app.feeds.fetch(target.url), target.id); successes++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { store.feedError(target.id, e.message ?: "Refresh failed"); failures += target.name }
        }
        mutableNotice.value = "$successes feed(s) refreshed" + if (failures.isEmpty()) "" else "; failed: ${failures.joinToString()}"
    }
    fun subscribe(url: String) = network { subscribeNow(url); mutableNotice.value = "Feed added" }
    private suspend fun subscribeNow(url: String): String {
        val normalized = FeedParser.httpUrl(url.trim())
        return store.saveFeed(normalized, app.feeds.fetch(normalized))
    }
    fun importFeeds(uri: Uri) = network {
        val text = withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openInputStream(uri)).use {
                val bytes = it.readNBytes(1_048_577)
                require(bytes.size <= 1_048_576) { "Feed list must be smaller than 1 MB" }
                bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
        }
        val urls = FeedListFormat.urls(text)
        require(urls.size <= 1000) { "Import at most 1,000 feeds at a time" }
        var added = 0; var skipped = 0
        val failed = mutableListOf<String>()
        for (url in urls) {
            if (library.value.feeds.any { it.url == url }) { skipped++; continue }
            try { subscribeNow(url); added++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed += "$url: ${e.message}" }
        }
        mutableNotice.value = "$added added, $skipped already subscribed" + if (failed.isEmpty()) "" else ". Failed:\n${failed.joinToString("\n")}"
    }
    fun exportFeeds(uri: Uri) = action {
        val lines = library.value.feeds.filter { it.url.isNotBlank() }.map { FeedListFormat.line(it.url, it.name) }
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")) } }
        mutableNotice.value = "Feed list exported"
    }
    fun receive(text: String) { try { mutableSuggestion.value = Suggestions.parse(text) } catch (e: Exception) { app.report(e) } }
    fun dismissSuggestion() { mutableSuggestion.value = null }
    fun acceptSuggestion(suggestion: Suggestion, kind: String, subscribeFeed: Boolean) = network {
        if (kind == "feed") subscribeNow(suggestion.url)
        else {
            val feedId = if (subscribeFeed && suggestion.feedUrl != null) subscribeNow(suggestion.feedUrl) else null
            val id = store.addSharedAudio(suggestion.url, suggestion.title, feedId)
            store.download(id)
        }
        mutableSuggestion.value = null
        mutableNotice.value = if (kind == "feed") "Feed added" else "Episode download started"
    }
    fun clearNotice() { mutableNotice.value = null }
    private fun network(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        action { try { block() } finally { mutableBusy.value = false } }
    }
    override fun onCleared() { player.disconnect(); super.onCleared() }
}
