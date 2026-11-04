package com.pod4me.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pod4me.R
import kotlinx.coroutines.delay

/** One surface, attached to the same controller. Never forces the device's orientation. */
@Composable
internal fun FullscreenVideo(model: AppModel, onDismiss: () -> Unit) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    var controls by rememberSaveable { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var timerDialog by remember { mutableStateOf(false) }
    var seeking by remember(playback.id) { mutableStateOf<Float?>(null) }
    val context = LocalContext.current
    val window = context.activity()?.window
    val accessible = context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
    DisposableEffect(window) {
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldBehavior = insets?.systemBarsBehavior
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            oldBehavior?.let { insets?.systemBarsBehavior = it }
        }
    }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(playback.connected, playback.id, library.ready) {
        if (playback.connected && library.ready && library.episode(playback.id)?.isVideo != true) onDismiss()
    }
    LaunchedEffect(controls, playback.playing, interaction, timerDialog, seeking != null) {
        if (controls && playback.playing && !accessible && !timerDialog && seeking == null) { delay(3000); controls = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTapGestures { controls = !controls; interaction++ }
    }, contentAlignment = Alignment.Center) {
        val aspect = playback.videoAspectRatio.coerceIn(.1f, 10f)
        val fit = if (maxWidth / maxHeight > aspect) Modifier.height(maxHeight).width(maxHeight * aspect)
            else Modifier.width(maxWidth).height(maxWidth / aspect)
        VideoSurface(model.player.videoPlayer, aspect, playback.playing, fit)
        if (controls || accessible || !playback.playing) {
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .8f))
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(library.episode(playback.id)?.title.orEmpty(), Modifier.weight(1f), maxLines = 1)
                        SleepTimerControl(playback, model) { timerDialog = it }
                        IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), "Exit fullscreen") }
                    }
                    Slider(value = seeking ?: playback.position.toFloat().coerceIn(0f, playback.duration.coerceAtLeast(1).toFloat()),
                        enabled = playback.connected && playback.canSeek && playback.duration > 0,
                        onValueChange = { seeking = it; interaction++ }, valueRange = 0f..playback.duration.coerceAtLeast(1).toFloat(),
                        onValueChangeFinished = { seeking?.let { model.action { model.player.seek(it.toLong()) } }; seeking = null; interaction++ },
                        modifier = Modifier.semantics { contentDescription = "Video playback position" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Text("${playbackTime(playback.position)} / ${playbackTime(playback.duration)}", style = MaterialTheme.typography.labelSmall)
                        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color.White), enabled = playback.connected, onClick = { interaction++; model.action { model.player.back() } }) { Text("−10s") }
                        IconButton(enabled = playback.connected, onClick = { interaction++; model.action { model.player.toggle() } }) {
                            Icon(painterResource(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play), if (playback.playing) "Pause" else "Play")
                        }
                        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color.White), enabled = playback.connected, onClick = { interaction++; model.action { model.player.forward() } }) { Text("+10s") }
                    }
                }
            }
        }
    }
}
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
