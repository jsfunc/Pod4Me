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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import com.pod4me.data.LibraryBackup
import com.pod4me.data.PodcastDiscoveryClient
import java.io.File
import kotlin.math.roundToLong

data class StorageUi(val usedGb: Long = 0, val freeGb: Long = 0)
data class DiscoveryUi(val generation: Int = 0, val loading: Boolean = false,
    val items: List<PodcastRecommendation> = emptyList(), val message: String? = null)
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
    private val discoveryClient = PodcastDiscoveryClient()
    val discoveryMemory = app.discoveryPreferences.state
    private val mutableDiscovery = MutableStateFlow(DiscoveryUi())
    val discovery = mutableDiscovery.asStateFlow()

    init {
        viewModelScope.launch {
            library.map { state -> state.episodes.map { Triple(it.id, it.path, it.downloadId) } }.distinctUntilChanged()
                .flowOn(Dispatchers.Default).collect { updateStorage() }
        }
    }
    fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() }
        catch (e: SQLiteConstraintException) { app.report(IllegalArgumentException("The library changed while this action was being applied. Reopen the item and try again.")) }
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
        val targets = (feed?.let(::listOf) ?: library.value.subscriptions).filter { it.subscribed && it.url.isNotBlank() }
        val failures = mutableListOf<String>()
        for (target in targets) {
            try { store.saveFeed(target.url, app.feeds.fetch(target.url), target.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { store.feedError(target.id, e.message ?: "Refresh failed"); failures += target.name }
        }
        if (failures.isNotEmpty()) mutableNotice.value = "Refresh failed: ${failures.joinToString()}"
    }
    fun subscribe(url: String) = network { subscribeNow(url); mutableNotice.value = "Feed added" }
    fun discover() = network {
        val generation = mutableDiscovery.value.generation + 1
        mutableDiscovery.value = DiscoveryUi(generation, loading = true)
        try {
            val result = discoveryClient.discover(library.value, app.discoveryPreferences.read())
            app.discoveryPreferences.rememberShown(result.items)
            mutableDiscovery.value = DiscoveryUi(generation, items = result.items,
                message = if (result.partial) "Some directory requests failed; showing the fresh suggestions available."
                    else if (result.items.size < 10) "Only ${result.items.size} different suggestions available this time." else null)
        } catch (e: CancellationException) {
            mutableDiscovery.value = DiscoveryUi(generation, message = "Search interrupted. Tap Suggest to try again.")
            throw e
        } catch (e: Exception) {
            mutableDiscovery.value = DiscoveryUi(generation, message = e.message ?: "Could not load suggestions.")
        }
    }
    fun rateRecommendation(item: PodcastRecommendation, vote: Int) = action {
        app.discoveryPreferences.rate(item, vote)
    }
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
            if (library.value.subscriptions.any { it.url == url }) { skipped++; continue }
            try { subscribeNow(url); added++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed += "$url: ${e.message}" }
        }
        mutableNotice.value = "$added added, $skipped already subscribed" + if (failed.isEmpty()) "" else ". Failed:\n${failed.joinToString("\n")}"
    }
    fun exportFeeds(uri: Uri) = action {
        val lines = library.value.subscriptions.map { FeedListFormat.line(it.url, it.name) }
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")) } }
        mutableNotice.value = "Feed list exported"
    }
    fun exportLibrary(uri: Uri) = network {
        withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { store.exportLibrary(it) }
        }
        mutableNotice.value = "Library backup saved. Audio files are not included."
    }
    fun restoreLibrary(uri: Uri) = network {
        require(store.activeEpisode.get() == null) { "Stop playback in Player before restoring a backup" }
        withContext(Dispatchers.IO) {
            val temporary = File.createTempFile("restore-", ".json", app.cacheDir)
            try {
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= LibraryBackup.MAX_BYTES) { "Backup exceeds 256 MB" }
                        output.write(buffer, 0, count)
                    }
                } }
                temporary.inputStream().use { store.restoreLibrary(it) }
            } finally { temporary.delete() }
        }
        mutableNotice.value = "Library restored. Existing audio was kept; missing audio can be downloaded again from Latest or Queue."
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
