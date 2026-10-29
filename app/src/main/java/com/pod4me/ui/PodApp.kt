@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pod4me.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Html
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pod4me.R
import com.pod4me.domain.*
import com.pod4me.playback.PlaybackUi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PodApp(model: AppModel) {
    val library by model.library.collectAsStateWithLifecycle()
    val currentId by remember(model) { model.player.state.map { it.id }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = null)
    val storage by model.storage.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val suggestion by model.suggestion.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Library") }
    var filter by rememberSaveable { mutableStateOf("unheard") }
    var browseFeed by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(TrackSort.PUBLICATION) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddFeed by remember { mutableStateOf(false) }
    var editingFeed by remember { mutableStateOf<Feed?>(null) }
    var unsubscribingFeed by remember { mutableStateOf<Feed?>(null) }
    var showLists by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importFeeds) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(model::exportFeeds) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportLibrary) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF88D5B4))
        else lightColorScheme(primary = Color(0xFF176B52), secondary = Color(0xFF51675C), background = Color(0xFFF8FAF7))
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Pod4Me", fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("used: ${storage.usedGb} - free: ${storage.freeGb} GB",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }, actions = {
                        TextButton(onClick = { model.refresh() }, enabled = !busy && library.subscriptions.isNotEmpty()) { Text("Refresh") }
                    })
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                Column {
                    if (page != "Player") MiniPlayer(library, model) { page = "Player" }
                    NavigationBar {
                        listOf("Library" to R.drawable.ic_browse, "Latest" to R.drawable.ic_download, "Feeds" to R.drawable.ic_podcast, "Queue" to R.drawable.ic_queue_add, "Player" to R.drawable.ic_play).forEach { (name, icon) ->
                            NavigationBarItem(selected = page == name, onClick = { page = name; if (name == "Latest") browseFeed = null },
                                icon = { Icon(painterResource(icon), contentDescription = name) }, label = { Text(name) })
                        }
                    }
                }
            },
        ) { insets ->
            Column(Modifier.padding(insets).fillMaxSize()) {
                if (!library.ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else when (page) {
                    "Feeds" -> FeedScreen(library, busy, onAdd = { showAddFeed = true },
                        onImport = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                        onExport = { exportLauncher.launch("pod4me-feeds.txt") },
                        onBackup = { backupLauncher.launch("pod4me-library-v1.json") },
                        onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        onBrowse = { browseFeed = it.id; page = "Latest" }, onEdit = { editingFeed = it },
                        onRefresh = model::refresh, onSuggest = { share(context, Suggestions.feed(it)) }, onUnsubscribe = { unsubscribingFeed = it })
                    "Library", "Latest" -> {
                        val downloaded = page == "Library"
                        if (downloaded) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showLists = true }) { Text("Manage lists") }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All") })
                                library.lists.forEach { list -> FilterChip(selected = filter == list.id, onClick = { filter = list.id }, label = { Text(list.name) }) }
                                FilterChip(selected = filter == "starred", onClick = { filter = "starred"; sort = TrackSort.STARS }, label = { Text("Starred") })
                                library.feeds.filter { feed -> feed.subscribed || (library.downloadCounts[feed.id] ?: 0) > 0 }
                                    .forEach { feed -> FilterChip(selected = filter == "feed:${feed.id}", onClick = { filter = "feed:${feed.id}" }, label = { Text(feed.name) }) }
                            }
                            SortSelector(sort) { sort = it }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(browseFeed?.let(library::feedName) ?: "Latest episodes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                if (browseFeed != null) TextButton(onClick = { browseFeed = null }) { Text("All feeds") }
                            }
                            Text("Newest published first", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        val episodeIds = remember(library.catalogueRevision, page, filter, sort, browseFeed,
                            if (sort == TrackSort.PROGRESS && downloaded) library.episodes else null) {
                            (if (downloaded) library.episodes else library.latestEpisodes).filter { ep ->
                            if (!downloaded) browseFeed == null || ep.feedId == browseFeed
                            else (ep.isDownloaded || filter == "starred") && when {
                                filter == "all" -> true
                                filter == "unheard" -> !ep.heard
                                filter == "heard" -> ep.heard
                                filter == "starred" -> ep.stars > 0
                                filter.startsWith("feed:") -> ep.feedId == filter.removePrefix("feed:")
                                else -> filter in ep.lists
                            }
                        }.sorted(if (!downloaded) TrackSort.PUBLICATION else sort, library).map(Episode::id)
                        }
                        val episodes = remember(episodeIds, library.episodes) { episodeIds.mapNotNull(library::episode) }
                        val playOrder = remember(episodeIds, library.catalogueRevision) { episodes.filter(Episode::isDownloaded).map(Episode::id) }
                        if (episodes.isEmpty()) EmptyState(if (library.feeds.isEmpty()) "Add your first RSS feed in Feeds, or share a podcast link into Pod4Me." else if (downloaded) "No downloads in this view. Open Latest to download episodes." else "No episodes here. Press Refresh to check the feed.")
                        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(if (downloaded) 8.dp else 4.dp)) {
                            items(episodes, key = Episode::id) { ep -> EpisodeCard(ep, library.feedName(ep.feedId), currentId == ep.id,
                                onDetails = { selectedId = ep.id },
                                onPlay = { model.action { model.player.play(ep.id, playOrder) } },
                                onDownload = { model.action { model.store.download(ep.id) } },
                                onCancel = { model.action { model.store.cancelDownload(ep.id) } },
                                onQueue = { model.action { model.store.enqueue(ep.id) } }, compact = !downloaded,
                                artworkUrl = library.feedArtwork(ep.feedId)) }
                        }
                    }
                    "Queue" -> QueueScreen(library, model, onDetails = { selectedId = it })
                    "Player" -> PlayerScreen(library, model, onDetails = { selectedId = it })
                }
            }
        }
        restoreUri?.let { uri -> AlertDialog(onDismissRequest = { restoreUri = null },
            title = { Text("Restore library backup?") },
            text = { Text("This merges subscriptions, lists, ratings, progress, bookmarks and queue into this library. Backup values replace matching feed settings, ratings and progress; memberships are combined. Existing audio is kept. Missing audio is not downloaded automatically. Stop playback before restoring.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { model.restoreLibrary(uri); restoreUri = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } }) }
        if (showAddFeed) TextInputDialog("Subscribe to a podcast", "RSS feed URL", "", "Subscribe", busy,
            onDismiss = { showAddFeed = false }, onSave = { model.subscribe(it); showAddFeed = false })
        editingFeed?.let { feed -> FeedEditDialog(feed, library.lists, onDismiss = { editingFeed = null }) { name, days, defaults ->
            model.action { model.store.editFeed(feed.id, name, days, defaults); editingFeed = null }
        } }
        unsubscribingFeed?.let { feed -> AlertDialog(
            onDismissRequest = { unsubscribingFeed = null },
            title = { Text("Unsubscribe from ${feed.name}?") },
            text = { Text("This removes the feed from Feeds, Latest, refresh and export. Downloaded episodes, queued tracks, ratings, bookmarks and list memberships are kept. You can subscribe again using its URL.") },
            confirmButton = { TextButton(onClick = { model.action {
                model.store.unsubscribe(feed.id)
                if (browseFeed == feed.id) browseFeed = null
                unsubscribingFeed = null
            } }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { unsubscribingFeed = null }) { Text("Cancel") } },
        ) }
        if (showLists) ListsDialog(library.lists, model) { showLists = false }
        library.episode(selectedId)?.let { ep -> EpisodeDialog(ep, library, model, onDismiss = { selectedId = null }) }
        suggestion?.let { incoming -> SuggestionDialog(incoming, busy, onDismiss = model::dismissSuggestion) { kind, subscribe -> model.acceptSuggestion(incoming, kind, subscribe) } }
        error?.let { message -> AlertDialog(onDismissRequest = model.app::clearError, title = { Text("Could not complete action") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model.app::clearError) { Text("OK") } }) }
        notice?.let { message -> AlertDialog(onDismissRequest = model::clearNotice, title = { Text("Pod4Me") }, text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = model::clearNotice) { Text("OK") } }) }
    }
}

@Composable
private fun FeedScreen(library: Library, busy: Boolean, onAdd: () -> Unit, onImport: () -> Unit, onExport: () -> Unit,
    onBackup: () -> Unit, onRestore: () -> Unit, onBrowse: (Feed) -> Unit, onEdit: (Feed) -> Unit, onRefresh: (Feed) -> Unit, onSuggest: (Feed) -> Unit, onUnsubscribe: (Feed) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAdd, enabled = !busy) { Text("Add RSS feed") }
        TextButton(onClick = onImport, enabled = !busy) { Text("Import") }
        TextButton(onClick = onExport) { Text("Export") }
        TextButton(onClick = onBackup, enabled = !busy) { Text("Back up library") }
        TextButton(onClick = onRestore, enabled = !busy) { Text("Restore library") }
    }
    val visibleFeeds = library.feeds.filter { it.subscribed }
    if (visibleFeeds.isEmpty()) EmptyState("Subscribe with an RSS URL or import a text file containing one feed URL per line, optionally followed by [podcast title].")
    else LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(visibleFeeds, key = Feed::id) { feed ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FeedArtwork(feed.artworkUrl, 32.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(feed.name, modifier = Modifier.weight(1f).clickable { onBrowse(feed) },
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ActionIcon(R.drawable.ic_browse, "Browse episodes", onClick = { onBrowse(feed) })
                        ActionIcon(R.drawable.ic_settings, "Feed settings", onClick = { onEdit(feed) })
                        if (feed.url.isNotBlank()) ActionIcon(R.drawable.ic_refresh, "Refresh feed", enabled = !busy, onClick = { onRefresh(feed) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val total = library.episodeCounts[feed.id] ?: 0
                        val saved = library.downloadCounts[feed.id] ?: 0
                        Column(Modifier.weight(1f)) {
                            Text("${total} episodes · ${saved} saved", style = MaterialTheme.typography.labelSmall)
                            Text("Expiry: ${feed.expiryDays?.let { "$it days" } ?: "never"}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (feed.url.isNotBlank()) {
                            ActionIcon(R.drawable.ic_share, "Suggest feed", small = true, onClick = { onSuggest(feed) })
                            ActionIcon(R.drawable.ic_delete, "Unsubscribe from feed", small = true, onClick = { onUnsubscribe(feed) })
                        }
                    }
                    feed.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(@androidx.annotation.DrawableRes icon: Int, label: String, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(if (small) 20.dp else 24.dp)) }
    }
}

@Composable
private fun SortSelector(sort: TrackSort, onChange: (TrackSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { expanded = true }) { Text("Sort: ${sort.label} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

@Composable
private fun EpisodeCard(ep: Episode, feed: String, current: Boolean, onDetails: () -> Unit, onPlay: () -> Unit,
    onDownload: () -> Unit, onCancel: () -> Unit, onQueue: () -> Unit, compact: Boolean = false,
    artworkUrl: String? = null) {
    if (compact) {
        CompactEpisodeCard(ep, feed, current, artworkUrl, onDetails, onPlay, onDownload, onCancel, onQueue)
        return
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FeedArtwork(artworkUrl)
                Column(Modifier.weight(1f)) {
                    Text(feed + if (current) " · Current" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(ep.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onDetails))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${date(ep.published)} · ${if (ep.heard) "Heard" else "Unheard"}" +
                        (if (ep.stars > 0) " · ${"★".repeat(ep.stars)}" else ""), style = MaterialTheme.typography.bodySmall)
                    if (ep.isDownloaded) Text("${(ep.progress * 100).toInt()}% · ${if (ep.protected) "4ever" else ep.expiry?.let { "Expires ${date(it, includeYear = false)}" } ?: "No expiry"}",
                        style = MaterialTheme.typography.bodySmall)
                    else if (ep.downloadId != null) Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    ep.isDownloaded -> {
                        ActionIcon(R.drawable.ic_play, "Play episode", onClick = onPlay)
                        ActionIcon(R.drawable.ic_queue_add, "Add to queue", onClick = onQueue)
                    }
                    ep.downloadId != null -> ActionIcon(R.drawable.ic_close, "Cancel download", onClick = onCancel)
                    else -> ActionIcon(R.drawable.ic_download, "Download episode", onClick = onDownload)
                }
                ActionIcon(R.drawable.ic_help, "Episode details", onClick = onDetails)
            }
            ep.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CompactEpisodeCard(ep: Episode, feed: String, current: Boolean, artworkUrl: String?,
    onDetails: () -> Unit, onPlay: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onQueue: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedArtwork(artworkUrl, 36.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).clickable(onClick = onDetails).padding(vertical = 4.dp)) {
                    Text(feed, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    Text(ep.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when {
                    ep.isDownloaded -> {
                        IconButton(onClick = onPlay) { Icon(painterResource(R.drawable.ic_play), contentDescription = "Play episode") }
                        IconButton(onClick = onQueue) { Icon(painterResource(R.drawable.ic_queue_add), contentDescription = "Add to queue") }
                    }
                    ep.downloadId != null -> IconButton(onClick = onCancel) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel download")
                    }
                    else -> IconButton(onClick = onDownload) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = "Download episode")
                    }
                }
                IconButton(onClick = onDetails) { Icon(painterResource(R.drawable.ic_help), contentDescription = "Episode details") }
            }
            Text("${date(ep.published)} · ${if (ep.heard) "Heard" else "Unheard"}" +
                (if (current) " · Current" else "") + (if (ep.stars > 0) " · ${"★".repeat(ep.stars)}" else ""),
                style = MaterialTheme.typography.bodySmall)
            if (ep.isDownloaded) Text("${(ep.progress * 100).toInt()}% played · ${if (ep.protected) "4ever" else ep.expiry?.let { "Expires ${date(it)}" } ?: "No expiry"}",
                style = MaterialTheme.typography.bodySmall)
            else if (ep.downloadId != null) Text("Downloading…", style = MaterialTheme.typography.bodySmall)
            ep.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun QueueScreen(library: Library, model: AppModel, onDetails: (String) -> Unit) {
    Text("Up next", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
    Text("Queued episodes play first. When the queue empties, playback continues through the list you started from, in that order.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (library.queue.isEmpty()) EmptyState("The queue is empty. Use + Queue on a downloaded episode.")
    else LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(library.queue, key = { it }) { id -> library.episode(id)?.let { ep ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FeedArtwork(library.feedArtwork(ep.feedId), 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).clickable { onDetails(id) }) {
                        Text(ep.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(library.feedName(ep.feedId), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!ep.isDownloaded) ActionIcon(R.drawable.ic_download, "Download queued episode", enabled = ep.downloadId == null,
                        onClick = { model.action { model.store.download(id) } })
                    ActionIcon(R.drawable.ic_up, "Move up in queue", enabled = library.queue.first() != id,
                        onClick = { model.action { model.store.moveQueue(id, -1) } })
                    ActionIcon(R.drawable.ic_down, "Move down in queue", enabled = library.queue.last() != id,
                        onClick = { model.action { model.store.moveQueue(id, 1) } })
                    ActionIcon(R.drawable.ic_delete, "Remove from queue", onClick = { model.action { model.store.removeFromQueue(id) } })
                }
            }
        } }
    }
}

@Composable
private fun PlayerScreen(library: Library, model: AppModel, onDetails: (String) -> Unit) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val ep = library.episode(playback.id)
    var addBookmark by remember { mutableStateOf<Long?>(null) }
    var editBookmark by remember { mutableStateOf<Bookmark?>(null) }
    if (ep == null) { EmptyState("Choose a downloaded episode and tap Play."); return }
    var seeking by remember(ep.id) { mutableStateOf<Float?>(null) }
    var speed by remember(playback.speed) { mutableFloatStateOf(playback.speed) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeedArtwork(library.feedArtwork(ep.feedId), 64.dp)
            Column(Modifier.weight(1f)) {
                Text(library.feedName(ep.feedId), color = MaterialTheme.colorScheme.primary)
                Text(ep.title, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Slider(value = seeking ?: playback.position.toFloat().coerceIn(0f, playback.duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { seeking = it }, valueRange = 0f..playback.duration.toFloat().coerceAtLeast(1f), enabled = playback.duration > 0,
            onValueChangeFinished = { seeking?.let { value -> model.action { model.player.seek(value.toLong()) } }; seeking = null })
        Text("${time(seeking?.toLong() ?: playback.position)} / ${time(playback.duration)}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.action { model.player.previous() } }, enabled = playback.hasPrevious) {
                Icon(painterResource(R.drawable.ic_previous), contentDescription = "Previous episode")
            }
            IconButton(onClick = { model.action { model.player.back() } }, modifier = Modifier.semantics { contentDescription = "Back 10 seconds" }) { Text("−10s", maxLines = 1) }
            FilledIconButton(onClick = { model.action { model.player.toggle() } }) {
                Icon(painterResource(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = if (playback.playing) "Pause" else "Play")
            }
            IconButton(onClick = { model.action { model.player.forward() } }, modifier = Modifier.semantics { contentDescription = "Forward 10 seconds" }) { Text("+10s", maxLines = 1) }
            IconButton(onClick = { model.action { model.player.next() } }, enabled = playback.hasNext) {
                Icon(painterResource(R.drawable.ic_next), contentDescription = "Next episode")
            }
        }
        Text("Speed: ${String.format(Locale.US, "%.1f", speed)}×")
        Slider(value = speed, onValueChange = { speed = it }, valueRange = 1f..3f, steps = 19,
            onValueChangeFinished = { model.action { model.player.speed(speed) } })
        FlowRow {
            TextButton(onClick = { onDetails(ep.id) }) { Text("Episode details") }
            TextButton(onClick = { addBookmark = playback.position }) { Text("Add bookmark") }
            TextButton(onClick = { model.action { model.player.stop() } }) { Text("Stop") }
        }
        HorizontalDivider()
        Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
        val bookmarks = library.bookmarks.filter { it.episodeId == ep.id }
        if (bookmarks.isEmpty()) Text("Save a moment to return to it later.", style = MaterialTheme.typography.bodySmall)
        bookmarks.forEach { mark ->
            Column {
                TextButton(onClick = { model.action { model.player.seek(mark.position) } }) { Text("${time(mark.position)}  ${mark.name}") }
                Row {
                    TextButton(onClick = { editBookmark = mark }) { Text("Rename") }
                    TextButton(onClick = { model.action { model.store.deleteBookmark(mark.id) } }) { Text("Delete") }
                }
            }
        }
    }
    addBookmark?.let { position -> TextInputDialog("Bookmark at ${time(position)}", "Name (optional)", "", "Save", allowEmpty = true,
        onDismiss = { addBookmark = null }, onSave = { name -> model.action { model.store.bookmark(ep.id, position, name) }; addBookmark = null }) }
    editBookmark?.let { mark -> TextInputDialog("Rename bookmark", "Name (optional)", mark.name, "Save", allowEmpty = true,
        onDismiss = { editBookmark = null }, onSave = { name -> model.action { model.store.renameBookmark(mark.id, name) }; editBookmark = null }) }
}

@Composable
private fun EpisodeDialog(ep: Episode, library: Library, model: AppModel, onDismiss: () -> Unit) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val info by produceState<String?>(null, ep.id, library.catalogueRevision) {
        value = withContext(Dispatchers.IO) { Html.fromHtml(model.store.description(ep.id), Html.FROM_HTML_MODE_COMPACT).toString().trim() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(ep.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(library.feedName(ep.feedId))
                Text("Published: ${date(ep.published)}", style = MaterialTheme.typography.bodySmall)
                ep.downloaded?.let { Text("Downloaded: ${date(it)}", style = MaterialTheme.typography.bodySmall) }
                Text(if (ep.protected) "Protected by 4ever" else ep.expiry?.let { "Expires: ${date(it)}" } ?: "No expiry", style = MaterialTheme.typography.bodySmall)
                Text("Rating", fontWeight = FontWeight.Bold)
                FlowRow {
                    (1..5).forEach { stars -> TextButton(onClick = { model.action { model.store.rate(ep.id, stars) } }) { Text(if (ep.stars == stars) "★ $stars" else "☆ $stars") } }
                    if (ep.stars > 0) TextButton(onClick = { model.action { model.store.rate(ep.id, 0) } }) { Text("Clear rating") }
                }
                if (ep.isDownloaded) {
                    Text("Lists", fontWeight = FontWeight.Bold)
                    Text("Feed: ${library.feedName(ep.feedId)} (automatic)", style = MaterialTheme.typography.bodySmall)
                    library.lists.forEach { list ->
                        val checked = when (list.id) { "unheard" -> !ep.heard; "heard" -> ep.heard; else -> list.id in ep.lists }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { included -> model.action {
                                if ((list.id == "unheard" && included) || (list.id == "heard" && !included)) model.player.markUnheard(ep.id)
                                else model.store.membership(ep.id, list.id, included)
                            } })
                            Text(list.name)
                        }
                    }
                    Text("Unchecking Unheard marks the episode heard. Marking it unheard resets its saved position.", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow {
                    TextButton(onClick = { copy(context, "Episode audio", ep.audioUrl) }) { Text("Share (copy audio URL)") }
                    TextButton(onClick = { share(context, Suggestions.episode(ep, library.feeds.find { it.id == ep.feedId })) }) { Text("Suggest to someone") }
                    TextButton(enabled = info != null, onClick = { copy(context, "Episode info", "${ep.title}\n\n${info.orEmpty()}") }) { Text("Copy info") }
                }
                SelectionContainer { Text(info?.ifBlank { "No episode information provided." } ?: "Loading episode information…") }
                val bookmarks = library.bookmarks.filter { it.episodeId == ep.id }
                if (bookmarks.isNotEmpty()) {
                    Text("Bookmarks", fontWeight = FontWeight.Bold)
                    bookmarks.forEach { mark ->
                        TextButton(enabled = ep.isDownloaded, onClick = {
                            model.action {
                                if (playback.id == ep.id) model.player.seek(mark.position)
                                else model.player.play(ep.id, listOf(ep.id), mark.position)
                            }
                            onDismiss()
                        }) { Text("${time(mark.position)} ${mark.name}") }
                    }
                }
                if (ep.isDownloaded) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !ep.protected && playback.id != ep.id) { Text("Delete downloaded audio", color = MaterialTheme.colorScheme.error) }
                    if (ep.protected) Text("Remove from 4ever to enable deletion.", style = MaterialTheme.typography.bodySmall)
                    else if (playback.id == ep.id) Text("Stop playback before deleting this episode.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete audio?") },
        text = { Text("Ratings, bookmarks and playback history will be kept. You can download the episode again.") },
        confirmButton = { TextButton(onClick = { model.action { model.store.deleteAudio(ep.id); onDismiss() }; confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun FeedEditDialog(feed: Feed, lists: List<TrackList>, onDismiss: () -> Unit, onSave: (String, Int?, Set<String>) -> Unit) {
    var name by remember(feed.id) { mutableStateOf(feed.name) }
    var forever by remember(feed.id) { mutableStateOf(feed.expiryDays == null) }
    var days by remember(feed.id) { mutableStateOf((feed.expiryDays ?: 30).toString()) }
    var defaults by remember(feed.id) { mutableStateOf(feed.defaults) }
    val validDays = days.toIntOrNull()?.let { it in 1..36500 } == true
    val valid = name.isNotBlank() && (forever || validDays)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Feed settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Feed name") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = forever, onCheckedChange = { forever = it }); Text("Never expire new downloads") }
            if (!forever) OutlinedTextField(value = days, onValueChange = { days = it }, label = { Text("Expiry in days (1–36500)") }, singleLine = true, isError = !validDays)
            Text("Applies to future downloads. Heard episodes expire within seven days; 4ever protects them.", style = MaterialTheme.typography.bodySmall)
            Text("Default lists", fontWeight = FontWeight.Bold)
            Text("New episodes automatically appear in Unheard and this feed’s view.", style = MaterialTheme.typography.bodySmall)
            lists.filter { it.id != "heard" && it.id != "unheard" }.forEach { list ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = list.id in defaults, onCheckedChange = { defaults = if (it) defaults + list.id else defaults - list.id })
                    Text(list.name)
                }
            }
            if (feed.url.isNotBlank()) SelectionContainer { Text(feed.url, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, if (forever) null else days.toInt(), defaults) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ListsDialog(lists: List<TrackList>, model: AppModel, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TrackList?>(null) }
    var deleting by remember { mutableStateOf<TrackList?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Manage lists") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            Text("Deleting a list keeps its audio. Unheard, Heard and 4ever are permanent.", style = MaterialTheme.typography.bodySmall)
            lists.forEach { list ->
                Text(list.name, Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                if (!list.system) Row {
                    TextButton(onClick = { renaming = list }) { Text("Rename") }
                    TextButton(onClick = { deleting = list }) { Text("Delete") }
                }
            }
            TextButton(onClick = { adding = true }) { Text("Create list") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if (adding) TextInputDialog("Create list", "Name", "", "Create", onDismiss = { adding = false }, onSave = { name -> model.action { model.store.createList(name); adding = false } })
    renaming?.let { list -> TextInputDialog("Rename list", "Name", list.name, "Save", onDismiss = { renaming = null }, onSave = { name -> model.action { model.store.renameList(list.id, name); renaming = null } }) }
    deleting?.let { list -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${list.name}?") }, text = { Text("The downloaded episodes will be kept.") },
        confirmButton = { TextButton(onClick = { model.action { model.store.deleteList(list.id); deleting = null } }) { Text("Delete list") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun SuggestionDialog(suggestion: Suggestion, busy: Boolean, onDismiss: () -> Unit, onAccept: (String, Boolean) -> Unit) {
    var kind by remember(suggestion) { mutableStateOf(if (suggestion.kind == "unknown") "feed" else suggestion.kind) }
    var subscribe by remember(suggestion) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Add suggestion to Pod4Me") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suggestion.title.isNotBlank()) Text(suggestion.title, fontWeight = FontWeight.Bold)
            SelectionContainer { Text(suggestion.url) }
            if (suggestion.kind == "unknown") {
                Text("What does this URL point to?")
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = kind == "feed", onClick = { kind = "feed" }); Text("RSS / Atom feed") }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = kind == "episode", onClick = { kind = "episode" }); Text("Direct audio file") }
                Text("Episode webpage links must be replaced with the feed or direct audio URL.", style = MaterialTheme.typography.bodySmall)
            }
            if (kind == "episode" && suggestion.feedUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = subscribe, onCheckedChange = { subscribe = it }); Text("Also subscribe to the source feed") }
            }
            if (kind == "episode") Text("This starts a download and may use mobile data.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onAccept(kind, subscribe) }, enabled = !busy) { Text(if (kind == "feed") "Subscribe" else "Download episode") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
private fun TextInputDialog(title: String, label: String, initial: String, confirm: String, busy: Boolean = false, allowEmpty: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = !busy && (allowEmpty || value.isNotBlank())) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable
private fun EmptyState(message: String) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(message, style = MaterialTheme.typography.bodyLarge) } }
private fun time(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy")
private val shortDate = DateTimeFormatter.ofPattern("d MMM")
private fun date(ms: Long, includeYear: Boolean = true) = if (ms <= 0) "Date unknown" else
    (if (includeYear) fullDate else shortDate).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
private fun copy(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
private fun share(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Suggest with…"))
}

@Composable
private fun MiniPlayer(library: Library, model: AppModel, onOpen: () -> Unit) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val ep = library.episode(playback.id) ?: return
    Surface(tonalElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FeedArtwork(library.feedArtwork(ep.feedId), 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${time(playback.position)} / ${time(playback.duration)}", style = MaterialTheme.typography.labelSmall)
            }
            ActionIcon(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play, if (playback.playing) "Pause" else "Play",
                onClick = { model.action { model.player.toggle() } })
        }
    }
}
