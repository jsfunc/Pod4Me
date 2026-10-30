package com.pod4me.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.pod4me.PodApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUi(val connected: Boolean = false, val id: String? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val hasNext: Boolean = false, val hasPrevious: Boolean = false,
    val buffering: Boolean = false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerClient(private val app: PodApplication) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val mutableState = MutableStateFlow(PlaybackUi())
    val state = mutableState.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }
    fun connect() {
        if (future != null) return
        val pending = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (future === pending) try {
                controller = pending.get().also { it.addListener(listener) }
                refresh()
            } catch (e: Exception) { future = null; app.report(e) }
        }, ContextCompat.getMainExecutor(app))
    }
    fun disconnect() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null; controller = null
        mutableState.value = mutableState.value.copy(connected = false)
    }
    fun refresh() {
        val p = controller ?: return
        mutableState.value = PlaybackUi(true, p.currentMediaItem?.mediaId,
            p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playerError == null, p.currentPosition,
            p.duration.coerceAtLeast(0), p.playbackParameters.speed, p.hasNextMediaItem(), p.hasPreviousMediaItem(),
            p.playbackState == Player.STATE_BUFFERING)
    }
    fun play(id: String, order: List<String>, position: Long = -1) {
        val args = Bundle().apply { putString("id", id); putStringArrayList("order", ArrayList(order)); putLong("position", position) }
        command(PlaybackService.START, args)
    }
    fun markUnheard(id: String) {
        command(PlaybackService.MARK_UNHEARD, Bundle().apply { putString("id", id) })
    }
    fun stop() { command(PlaybackService.STOP) }
    fun toggle() { command(PlaybackService.TOGGLE) }
    fun playQueue() { command(PlaybackService.PLAY_QUEUE) }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) {
        val result = requireController().sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        result.addListener({
            try {
                if (result.get().resultCode != SessionResult.RESULT_SUCCESS && app.error.value == null)
                    app.report(IllegalStateException("The player could not complete the action. Please try again."))
            } catch (e: Exception) { app.report(IllegalStateException("The player connection was interrupted. Please try again.", e)) }
        }, ContextCompat.getMainExecutor(app))
    }
    fun seek(position: Long) { requireController().seekTo(position.coerceAtLeast(0)) }
    fun back() { requireController().seekBack() }
    fun forward() { requireController().seekForward() }
    fun previous() { requireController().seekToPreviousMediaItem() }
    fun next() { requireController().seekToNextMediaItem() }
    fun speed(value: Float) { requireController().setPlaybackSpeed(value.coerceIn(1f, 3f)) }
    private fun requireController() = requireNotNull(controller) { "Player is connecting. Please try again in a moment." }
}
