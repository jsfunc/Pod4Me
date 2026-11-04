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

data class ImportPreview(val entries: List<SubscriptionFormat.Entry>, val already: Int, val invalid: Int, val duplicates: Int)
data class ImportUi(val completed: Int = 0, val total: Int = 0, val running: Boolean = false)
data class SubscriptionSearch(val items: List<PodcastRecommendation> = emptyList(), val loading: Boolean = false, val message: String? = null)
data class RefreshUi(val completed: Int = 0, val total: Int = 0, val running: Boolean = false, val failedIds: List<String> = emptyList())
data class StorageUi(val usedGb: Long = 0, val freeGb: Long = 0)
data class DiscoveryUi(val generation: Int = 0, val loading: Boolean = false,
    val items: List<PodcastRecommendation> = emptyList(), val message: String? = null)
class AppModel(application: Application) : AndroidViewModel(application) {
    val app = application as PodApplication
    val store = app.store
    val library = store.library
    private val mutableDownloadConfirmation = MutableStateFlow<Episode?>(null)
    private val waitingDownloadConfirmations = ArrayDeque<Episode>()
    val downloadConfirmation = mutableDownloadConfirmation.asStateFlow()
    fun requestDownload(id: String) = action { downloadOrConfirm(id) }
    private suspend fun downloadOrConfirm(id: String) {
        try { store.download(id) }
        catch (e: com.pod4me.data.DownloadConfirmationRequired) { queueDownloadConfirmation(e.episode) }
    }
    private fun queueDownloadConfirmation(ep: Episode) {
        if (mutableDownloadConfirmation.value?.id == ep.id || waitingDownloadConfirmations.any { it.id == ep.id }) return
        if (mutableDownloadConfirmation.value == null) mutableDownloadConfirmation.value = ep
        else waitingDownloadConfirmations.addLast(ep)
    }
    fun dismissDownloadConfirmation() { mutableDownloadConfirmation.value = waitingDownloadConfirmations.removeFirstOrNull() }
    fun confirmDownload() {
        val ep = mutableDownloadConfirmation.value ?: return
        dismissDownloadConfirmation()
        action { store.download(ep.id, allowLargeMetered = true) }
    }
    val radios = app.radios.state
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
    private val mutableSearch = MutableStateFlow(SubscriptionSearch())
    val subscriptionSearch = mutableSearch.asStateFlow()
    private val mutableImport = MutableStateFlow<ImportPreview?>(null)
    val importPreview = mutableImport.asStateFlow()
    private val mutableImportProgress = MutableStateFlow(ImportUi())
    val importProgress = mutableImportProgress.asStateFlow()
    private val mutableRefresh = MutableStateFlow(RefreshUi())
    val refreshState = mutableRefresh.asStateFlow()
    fun clearSearch() { mutableSearch.value = SubscriptionSearch() }
    fun searchSubscriptions(term: String) = network {
        mutableSearch.value = SubscriptionSearch(loading = true)
        try {
            val items = discoveryClient.search(term)
            mutableSearch.value = SubscriptionSearch(items, message = if (items.isEmpty()) "No subscribable feeds found. Try another name or paste the show's RSS URL." else null)
        } catch (e: CancellationException) { mutableSearch.value = SubscriptionSearch(); throw e }
        catch (e: Exception) { mutableSearch.value = SubscriptionSearch(message = e.message ?: "Search failed") }
    }
    fun subscribeResult(item: PodcastRecommendation) = network { subscribeNow(item.feedUrl) }
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
    var needsForegroundMaintenance = true
    suspend fun onLaunch() {
        if (!needsForegroundMaintenance) return
        try {
            app.initializeOnce()
            store.reconcileDownloads(); store.expireAtLaunch(); updateStorage()
            needsForegroundMaintenance = false
        } catch (e: Exception) { app.report(e) }
    }
    private suspend fun updateStorage() = withContext(Dispatchers.IO) {
        val directory = app.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS) ?: app.filesDir
        val bytes = directory.listFiles()?.sumOf(File::length) ?: 0L
        mutableStorage.value = StorageUi((bytes / 1e9).roundToLong(), (StatFs(directory.absolutePath).availableBytes / 1e9).roundToLong())
    }
    fun refresh(feed: Feed? = null) = refreshFeeds(feed?.let(::listOf) ?: library.value.subscriptions)
    fun retryRefresh() = refreshFeeds(library.value.subscriptions.filter { it.id in mutableRefresh.value.failedIds })
    fun dismissRefreshFailures() { mutableRefresh.value = mutableRefresh.value.copy(failedIds = emptyList()) }
    private fun refreshFeeds(targets: List<Feed>) = network {
        val previousFailures = mutableRefresh.value.failedIds
        val refresher = com.pod4me.data.FeedRefresher(app.feeds::refresh,
            { feed, response ->
                store.applyRefresh(feed, response)
                store.startAutoDownloads(feed.id).forEach(::queueDownloadConfirmation)
            }, { feed, message -> store.feedError(feed.id, message) })
        try {
            val failed = refresher.refresh(targets) { done, total -> mutableRefresh.value = RefreshUi(done, total, true) }
            mutableRefresh.value = mutableRefresh.value.copy(running = false, failedIds = remainingRefreshFailures(previousFailures, targets.map { it.id }, failed.map { it.id }, library.value.subscriptions.map { it.id }))
        } finally { mutableRefresh.value = mutableRefresh.value.copy(running = false) }
    }
    fun subscribe(url: String) = network { subscribeNow(url); mutableNotice.value = "Subscription added" }
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
    private suspend fun subscribeNow(url: String, title: String? = null): String {
        if (YouTubeChannels.isYouTube(url)) return store.subscribeYouTube(url, title)
        val normalized = discoveryClient.resolveAppleUrl(url.trim()) ?: FeedParser.httpUrl(url.trim())
        return requireNotNull(store.saveSubscription(normalized, app.feeds.fetch(normalized), title))
    }
    private suspend fun importSubscription(entry: SubscriptionFormat.Entry): Boolean {
        val source = entry.url()
        val url = if (YouTubeChannels.isYouTube(source)) YouTubeChannels.normalize(source)
            else discoveryClient.resolveAppleUrl(source) ?: FeedParser.httpUrl(source)
        if (library.value.subscriptions.any { it.url == url }) return false
        val parsed = if (YouTubeChannels.isYouTube(url)) FeedParser.Feed(YouTubeChannels.defaultName(url), emptyList())
            else app.feeds.fetch(url)
        return store.saveSubscription(url, parsed, entry.title(), skipSubscribed = true) != null
    }

    fun importFeeds(uri: Uri) = network {
        val entries = withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openInputStream(uri)).use { SubscriptionFormat.parse(it.readNBytes(SubscriptionFormat.MAX_BYTES + 1)) }
        }
        mutableImport.value = previewImport(entries, library.value.subscriptions.map { it.url }.toSet())
    }
    fun dismissImport() { mutableImport.value = null }
    fun confirmImport() = network {
        val preview = mutableImport.value ?: return@network
        mutableImport.value = null
        try {
            val result = com.pod4me.data.SubscriptionImporter(::importSubscription).run(preview.entries) { done, total ->
                mutableImportProgress.value = ImportUi(done, total, running = true)
            }
            mutableNotice.value = "${result.added} added, ${result.skipped + preview.already} already subscribed, ${preview.invalid} invalid" +
                if (result.failures.isEmpty()) "" else ". Failed:\n${result.failures.joinToString("\n")}"
        } finally { mutableImportProgress.value = mutableImportProgress.value.copy(running = false) }
    }

    fun exportFeeds(uri: Uri, opml: Boolean = false) = action {
        val feeds = library.value.subscriptions.filter { !opml || !it.isYouTube }
        val text = if (opml) SubscriptionFormat.opml(feeds.map { SubscriptionFormat.Entry(it.url, it.name) })
            else feeds.joinToString("\n", postfix = if (feeds.isEmpty()) "" else "\n") { FeedListFormat.line(it.url, it.name) }
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) } }
        mutableNotice.value = "Feed list exported"
    }
    fun deleteListDownloads(filter: String, ids: Set<String>, alreadySkipped: Int = 0) = action {
        val result = store.deleteListDownloads(filter, ids)
        mutableNotice.value = "${result.deleted} downloads deleted, ${result.skipped + alreadySkipped} skipped" +
            if (result.failed > 0) ", ${result.failed} failed (see episode errors)" else ""
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
            if (subscribeFeed && suggestion.feedUrl != null) subscribeNow(suggestion.feedUrl)
            val id = store.addSharedAudio(suggestion.url, suggestion.title)
            downloadOrConfirm(id)
        }
        mutableSuggestion.value = null
        mutableNotice.value = if (kind == "feed") "Feed added" else if (mutableDownloadConfirmation.value == null) "Episode download started" else null
    }
    fun clearNotice() { mutableNotice.value = null }
    private fun network(block: suspend () -> Unit) {
        if (mutableBusy.value) {
            mutableNotice.value = "Please wait for the current operation to finish, then try again."
            return
        }
        mutableBusy.value = true
        action { try { block() } finally { mutableBusy.value = false } }
    }
    companion object {
        internal fun remainingRefreshFailures(previous: List<String>, attempted: List<String>, failed: List<String>, subscribed: List<String>): List<String> =
            ((previous - attempted.toSet()) + failed).distinct().filter { it in subscribed }
        internal fun subscriptionUrl(input: String): String? {
            val value = input.trim()
            if (value.contains("://")) return value
            if (value.any(Char::isWhitespace) || '/' !in value) return null
            val candidate = "https://$value"
            val host = runCatching { java.net.URI(candidate).host }.getOrNull() ?: return null
            return candidate.takeIf { '.' in host }
        }
        internal fun previewImport(entries: List<SubscriptionFormat.Entry>, subscribed: Set<String>): ImportPreview {
            val pending = linkedMapOf<String, SubscriptionFormat.Entry>()
            val seen = mutableSetOf<String>()
            var already = 0; var invalid = 0; var duplicates = 0
            for (entry in entries) {
                try {
                    val url = if (YouTubeChannels.isYouTube(entry.url())) YouTubeChannels.normalize(entry.url()) else FeedParser.httpUrl(entry.url())
                    if (!seen.add(url)) duplicates++
                    else if (url in subscribed) already++
                    else pending[url] = SubscriptionFormat.Entry(url, entry.title())
                } catch (_: Exception) { invalid++ }
            }
            return ImportPreview(pending.values.toList(), already, invalid, duplicates)
        }
    }
    override fun onCleared() { player.disconnect(); super.onCleared() }
}
