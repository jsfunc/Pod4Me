package com.pod4me.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.pod4me.PodApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUi(val connected: Boolean = false, val id: String? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val hasNext: Boolean = false, val hasPrevious: Boolean = false)

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
        mutableState.value = PlaybackUi(true, p.currentMediaItem?.mediaId, p.isPlaying, p.currentPosition,
            p.duration.coerceAtLeast(0), p.playbackParameters.speed, p.hasNextMediaItem(), p.hasPreviousMediaItem())
    }
    fun play(id: String, order: List<String>, position: Long = -1) {
        val args = Bundle().apply { putString("id", id); putStringArrayList("order", ArrayList(order)); putLong("position", position) }
        requireController().sendCustomCommand(SessionCommand(PlaybackService.START, Bundle.EMPTY), args)
    }
    fun markUnheard(id: String) {
        requireController().sendCustomCommand(SessionCommand(PlaybackService.MARK_UNHEARD, Bundle.EMPTY), Bundle().apply { putString("id", id) })
    }
    fun stop() { requireController().sendCustomCommand(SessionCommand(PlaybackService.STOP, Bundle.EMPTY), Bundle.EMPTY) }
    fun toggle() { requireController().let {
        if (it.isPlaying) it.pause() else {
            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    } }
    fun seek(position: Long) { requireController().seekTo(position.coerceAtLeast(0)) }
    fun back() { requireController().seekBack() }
    fun forward() { requireController().seekForward() }
    fun previous() { requireController().seekToPreviousMediaItem() }
    fun next() { requireController().seekToNextMediaItem() }
    fun speed(value: Float) { requireController().setPlaybackSpeed(value.coerceIn(1f, 3f)) }
    private fun requireController() = requireNotNull(controller) { "Player is connecting. Please try again in a moment." }
}
