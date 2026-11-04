package com.pod4me.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pod4me.R
import com.pod4me.domain.RadioCatalogue
import com.pod4me.domain.RadioStation
import com.pod4me.playback.PlaybackUi

@Composable
internal fun RadioScreen(model: AppModel, positions: ScrollPositions) {
    val stations by model.radios.collectAsStateWithLifecycle()
    val playback by model.player.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live radio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { adding = true }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Subscribe to a radio")
            }
        }
        if (stations.isEmpty()) Text("Tap + to subscribe to a radio.", Modifier.padding(16.dp))
        else {
            Text("Hold and drag to reorder", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            val listState = positions.list("radios", stations.map { it.id })
            val drag = remember(listState) { RadioDragState(listState) }
            val currentStations by rememberUpdatedState(stations)
            val shown = drag.preview ?: stations
            val edge = with(LocalDensity.current) { 64.dp.toPx() }
            val speed = with(LocalDensity.current) { 700.dp.toPx() }
            LaunchedEffect(drag.id) {
                if (drag.id != null) {
                    var previous = withFrameNanos { it }
                    while (drag.id != null) {
                        val now = withFrameNanos { it }
                        val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                        previous = now
                        val delta = drag.edgeFraction(edge) * speed * seconds
                        if (delta != 0f) { listState.scrollBy(delta); drag.move(0f) }
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize().pointerInput(drag) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.start(it.y, currentStations) },
                    onDrag = { change, amount -> if (drag.id != null) { change.consume(); drag.move(amount.y) } },
                    onDragCancel = { if (drag.id != null) drag.reset() },
                    onDragEnd = {
                        drag.finish()?.let { ids -> model.action {
                            try { model.app.radios.reorder(ids) } finally { drag.reset() }
                        } }
                    },
                )
            }, state = listState, contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { station ->
                    val active = playback.id == station.id
                    val dragging = drag.id == station.id
                    val index = shown.indexOf(station)
                    OutlinedCard(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) drag.translation() else 0f }
                        .semantics {
                            customActions = if (drag.id != null || drag.saving) emptyList() else buildList {
                                fun move(to: Int): Boolean {
                                    val order = shown.toMutableList().apply { add(to, removeAt(index)) }
                                    model.action { model.app.radios.reorder(order.map { it.id }) }
                                    return true
                                }
                                if (index > 0) add(CustomAccessibilityAction("Move up") { move(index - 1) })
                                if (index < shown.lastIndex) add(CustomAccessibilityAction("Move down") { move(index + 1) })
                            }
                        }) {
                        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_drag), contentDescription = "Hold and drag ${station.name} to reorder",
                                modifier = Modifier.size(40.dp).padding(8.dp),
                                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(station.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (active) Text(radioStatus(playback),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { removingId = station.id }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Unsubscribe from ${station.name}")
                            }
                            FilledIconButton(enabled = playback.connected, onClick = { model.action {
                                if (active && playback.playing) model.player.stop() else if (active) model.player.toggle() else model.player.playRadio(station.id)
                            } }) {
                                Icon(painterResource(if (active && playback.playing) R.drawable.ic_stop else R.drawable.ic_play),
                                    contentDescription = if (active && playback.playing) "Stop ${station.name}" else "Play ${station.name}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) RadioPicker(model, stations) { adding = false }
    stations.firstOrNull { it.id == removingId }?.let { station ->
        AlertDialog(onDismissRequest = { removingId = null }, title = { Text("Unsubscribe from ${station.name}?") },
            text = { Text("You can add this station again with +.") },
            confirmButton = { TextButton(onClick = { model.action {
                if (model.player.state.value.id == station.id) model.player.stop()
                model.app.radios.unsubscribe(station.id)
                removingId = null
            } }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RadioPicker(model: AppModel, subscribed: List<RadioStation>, onDismiss: () -> Unit) {
    var custom by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun subscribe(station: RadioStation) {
        saving = true
        model.action {
            try { model.app.radios.subscribe(station.name, station.url); onDismiss() }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Subscribe to a radio") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !custom, onClick = { custom = false }, modifier = Modifier.weight(1f), label = { Text("Stations") })
                FilterChip(selected = custom, onClick = { custom = true }, modifier = Modifier.weight(1f), label = { Text("Custom URL") })
            }
            if (custom) Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(120); error = null }, label = { Text("Station name") }, singleLine = true)
                OutlinedTextField(url, { url = it.take(4096); error = null }, label = { Text("Stream URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                Text("Paste a direct HTTP or HTTPS audio stream (MP3, AAC or HLS .m3u8), rather than the station’s website or podcast feed.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else {
                OutlinedTextField(query, { query = it.take(120) }, label = { Text("Find a station or country") }, singleLine = true)
                val groups = RadioCatalogue.groups.map { (country, stations) ->
                    country to stations.filter { country.contains(query.trim(), ignoreCase = true) || it.name.contains(query.trim(), ignoreCase = true) }
                }.filter { it.second.isNotEmpty() }
                if (groups.isEmpty()) Text("No match. Use Custom URL to add another station.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    groups.forEach { (country, stations) ->
                        item(key = "country:$country") {
                            Text(country, Modifier.padding(top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        items(stations, key = { it.id }) { station ->
                            val already = subscribed.any { it.id == station.id }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(station.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (already) Text("Added", style = MaterialTheme.typography.labelSmall)
                                else IconButton(enabled = !saving, onClick = { subscribe(station) }) {
                                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Subscribe to ${station.name}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        if (custom) TextButton(enabled = !saving && name.isNotBlank() && url.isNotBlank(), onClick = {
            try {
                val station = RadioStation.create(name, url)
                if (subscribed.any { it.id == station.id }) error = "You already subscribe to this stream."
                else subscribe(station)
            } catch (e: IllegalArgumentException) { error = e.message }
        }) { Text("Subscribe") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Close") } })
}

@Composable
fun LivePlayer(playback: PlaybackUi, model: AppModel) {
    var seeking by remember(playback.id) { mutableStateOf<Float?>(null) }
    val behind = (playback.duration - (seeking?.toLong() ?: playback.position)).coerceAtLeast(0)
    fun duration(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(R.drawable.ic_live), contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(playback.radioName.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        Text(if (seeking != null) "${duration(behind)} behind live" else radioStatus(playback))
        Slider(value = seeking ?: playback.position.toFloat().coerceIn(0f, playback.duration.toFloat().coerceAtLeast(1f)),
            modifier = Modifier.semantics { contentDescription = "Rewind buffered live radio" },
            onValueChange = { seeking = it }, valueRange = 0f..playback.duration.toFloat().coerceAtLeast(1f),
            enabled = playback.canSeek && playback.duration > 0,
            onValueChangeFinished = { seeking?.let { value -> model.action { model.player.seek(value.toLong()) } }; seeking = null })
        Text("${duration(playback.duration)} available · up to 30 minutes", style = MaterialTheme.typography.bodySmall)
        Text("Rewind starts when you tune in. Stop or change station to clear it.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = playback.canSeek, onClick = { model.action { model.player.back() } }) { Text("−10s") }
            Button(enabled = playback.connected, onClick = { model.action { model.player.toggle() } }) { Text(if (playback.playing) "Pause" else if (playback.ended) "Replay" else "Resume") }
            OutlinedButton(enabled = playback.canSeek, onClick = { model.action { model.player.forward() } }) { Text("+10s") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SleepTimerControl(playback, model)
            Button(onClick = { model.action { model.player.goLive() } }) { Text("Back to live") }
            Button(onClick = { model.action { model.player.stop() } }) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
}

internal fun radioStatus(playback: PlaybackUi): String = when {
    playback.radioStatus == "PAUSE_LIMIT" -> "Capture stopped after 30 min paused · Back to live reconnects"
    playback.radioStatus == "UNAVAILABLE" -> if (playback.radioHasAudio) "Station unavailable · Buffer kept · Back to live retries" else "Station unavailable · Back to live retries"
    playback.radioStatus == "RECONNECTING" -> "Reconnecting…"
    playback.ended -> "Buffer finished · Replay or Back to live"
    playback.radioNotice != null -> playback.radioNotice
    playback.buffering -> "Buffering…"
    !playback.playing -> "Paused"
    playback.liveOffset <= 8000 -> "Live"
    else -> "%d:%02d behind live".format(playback.liveOffset / 60000, playback.liveOffset / 1000 % 60)
}
