package com.pod4me.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pod4me.R
import com.pod4me.playback.PlaybackUi
import kotlin.math.roundToInt

@Composable
internal fun SleepTimerControl(playback: PlaybackUi, model: AppModel, onDialogVisibility: (Boolean) -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableFloatStateOf(15f) }
    var episodeEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(open) { onDialogVisibility(open) }
    val running = playback.sleepRemaining >= 0 || playback.sleepEnd
    fun close() { open = false; onDialogVisibility(false) }
    val remaining = sleepTimerRemaining(playback)
    val label = if (playback.sleepEnd && playback.duration <= 0) "End" else playbackTime(remaining)
    val edit = {
        minutes = if (playback.sleepRemaining >= 0) ((playback.sleepRemaining + 59_999) / 60_000).toFloat().coerceIn(1f, 120f) else 15f
        episodeEnd = playback.sleepEnd; open = true; onDialogVisibility(true)
    }
    if (running) TextButton(onClick = edit, enabled = playback.connected,
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
        modifier = Modifier.semantics { contentDescription = "Sleep timer: $label. Change or cancel" }) { Text(label) }
    else IconButton(onClick = edit, enabled = playback.connected && playback.id != null) {
        Icon(painterResource(R.drawable.ic_timer), contentDescription = "Sleep timer")
    }
    if (open) AlertDialog(onDismissRequest = ::close, title = { Text("Sleep timer") }, text = {
        Column {
            Text(if (episodeEnd) "Pause at the end of this episode" else "${minutes.roundToInt()} minutes")
            Slider(value = minutes, onValueChange = { minutes = it; episodeEnd = false }, valueRange = 1f..120f, steps = 118,
                modifier = Modifier.semantics { contentDescription = "Timer duration in minutes" })
            if (playback.radioName == null) Row {
                Checkbox(checked = episodeEnd, onCheckedChange = { episodeEnd = it })
                Text("End of episode", Modifier.padding(top = 12.dp))
            }
            if (playback.radioName != null) Text("Stops radio capture and clears the temporary buffer.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { model.action { model.player.sleepTimer(if (episodeEnd) 0 else minutes.roundToInt() * 60_000L) }; close() }) {
            Text(if (running) "Update" else "Start")
        }
    }, dismissButton = {
        Row {
            if (running) TextButton(onClick = { model.action { model.player.sleepTimer(-1) }; close() }) { Text("Cancel timer") }
            TextButton(onClick = ::close) { Text("Close") }
        }
    })
}

internal fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun sleepTimerRemaining(playback: PlaybackUi): Long = if (playback.sleepEnd)
    ((playback.duration - playback.position).coerceAtLeast(0) / playback.speed.coerceAtLeast(.1f)).toLong()
    else playback.sleepRemaining
