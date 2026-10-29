package com.pod4me.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.pod4me.MainActivity
import com.pod4me.PodApplication
import com.pod4me.domain.Episode
import com.pod4me.domain.Policy
import com.pod4me.domain.Upcoming
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/** The service owns playback; the activity is only a MediaController client. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as PodApplication
    private val store get() = app.store
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var contextOrder = emptyList<String>()
    private var contextCursor = -1
    private var changing = false
    private var started = false
    private val operations = Mutex()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.setPlaybackSpeed(preferences.getFloat("speed", 1f).coerceIn(1f, 3f))
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (changing) return
                oldPosition.mediaItem?.mediaId?.let { id ->
                    val savedDuration = store.library.value.episode(id)?.duration ?: 0
                    val duration = if (savedDuration > 0) savedDuration else player.currentTimeline.let { timeline ->
                        if (oldPosition.mediaItemIndex in 0 until timeline.windowCount)
                            timeline.getWindow(oldPosition.mediaItemIndex, Timeline.Window()).durationMs else 0L
                    }
                    scope.launch { safely { store.progress(id, oldPosition.positionMs, duration) } }
                    if (newPosition.mediaItem?.mediaId == id) {
                        scope.launch { safely { store.progress(id, newPosition.positionMs, duration) } }
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (changing || mediaItem == null) return
                val id = mediaItem.mediaId
                store.activeEpisode.set(id)
                val index = contextOrder.indexOf(id)
                if (index >= 0) contextCursor = index
                persistContext(id)
                val ep = store.library.value.episode(id)
                if (ep != null) {
                    changing = true
                    try { player.seekTo(Policy.resumePosition(ep.position, ep.duration)) }
                    finally { changing = false }
                }
                scope.launch { safely { store.removeFromQueue(id); rebuildUpcoming() } }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!changing) saveProgress() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) saveProgress()
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                preferences.edit().putFloat("speed", playbackParameters.speed).apply()
            }
            override fun onPlayerError(error: PlaybackException) { app.report(IllegalStateException("Playback failed: ${error.errorCodeName}. Try downloading this episode again.")) }
        })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val base = super.onConnect(session, controller)
                    val commands = base.availableSessionCommands.buildUpon()
                        .add(SessionCommand(BACK10, Bundle.EMPTY)).add(SessionCommand(FORWARD10, Bundle.EMPTY))
                    if (controller.packageName == packageName) {
                        commands.add(SessionCommand(START, Bundle.EMPTY)).add(SessionCommand(STOP, Bundle.EMPTY))
                    }
                    // System controls may seek/play/pause, but arbitrary external media cannot be injected.
                    val playerCommands = base.availablePlayerCommands.buildUpon()
                        .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build()).setAvailablePlayerCommands(playerCommands).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (controller.packageName != packageName && customCommand.customAction !in setOf(BACK10, FORWARD10))
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    val result = SettableFuture.create<SessionResult>()
                    scope.launch {
                        try {
                            operations.withLock { when (customCommand.customAction) {
                                START -> {
                                    val id = requireNotNull(args.getString("id"))
                                    val order = args.getStringArrayList("order") ?: arrayListOf(id)
                                    startEpisode(id, order, args.getLong("position", -1L))
                                }
                                STOP -> {
                                    saveProgressNow()
                                    player.stop(); player.clearMediaItems()
                                    store.activeEpisode.set(null)
                                    preferences.edit().remove("current").apply()
                                }
                                BACK10 -> player.seekBack()
                                FORWARD10 -> player.seekForward()
                                else -> error("Unknown playback command")
                            } }
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        } catch (e: Exception) {
                            app.report(e)
                            result.set(SessionResult(SessionError.ERROR_UNKNOWN))
                        }
                    }
                    return result
                }
            }).setMediaButtonPreferences(ImmutableList.of(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setDisplayName("Back 10 seconds")
                    .setSessionCommand(SessionCommand(BACK10, Bundle.EMPTY)).setSlots(CommandButton.SLOT_OVERFLOW).build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setDisplayName("Forward 10 seconds")
                    .setSessionCommand(SessionCommand(FORWARD10, Bundle.EMPTY)).setSlots(CommandButton.SLOT_OVERFLOW).build(),
            )).build()
        scope.launch {
            store.library.map { library -> library.queue to library.episodes.filter { it.isDownloaded }.map { it.id } }
                .distinctUntilChanged().collect { if (started) rebuildUpcoming() }
        }
        scope.launch {
            while (isActive) { delay(5_000); if (player.isPlaying) saveProgressNow() }
        }
        scope.launch {
            safely {
                store.initialize()
                operations.withLock { if (!started) {
                    val last = preferences.getString("current", null)
                    val ep = store.library.value.episode(last)
                    if (ep?.path != null && File(ep.path).isFile) {
                        val order = decodeOrder(preferences.getString("order", "[]") ?: "[]")
                        contextCursor = preferences.getInt("cursor", -1)
                        startEpisode(ep.id, order, -1, play = false)
                    }
                } }
            }
        }
    }

    private suspend fun startEpisode(id: String, order: List<String>, position: Long, play: Boolean = true) {
        saveProgressNow()
        val ep = store.activate(id)
        started = true
        contextOrder = order.distinct()
        contextCursor = contextOrder.indexOf(id).takeIf { it >= 0 } ?: if (play) -1 else contextCursor
        changing = true
        try {
            player.setMediaItem(item(ep), if (position >= 0) position else Policy.resumePosition(ep.position, ep.duration))
            player.prepare()
        } finally { changing = false }
        persistContext(id)
        store.removeFromQueue(id)
        rebuildUpcoming()
        if (play) player.play()
    }

    private fun rebuildUpcoming() {
        val id = player.currentMediaItem?.mediaId ?: return
        val library = store.library.value
        val history = (0..player.currentMediaItemIndex).map { player.getMediaItemAt(it).mediaId }.toSet()
        val available = library.episodes.filter { it.path != null && File(it.path).isFile }.map { it.id }.toSet()
        val upcoming = Upcoming.plan(library.queue, contextOrder, contextCursor, id, history, available).mapNotNull(library::episode)
        val old = (player.currentMediaItemIndex + 1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (old == upcoming.map { it.id }) return
        changing = true
        try {
            player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
            player.addMediaItems(upcoming.map(::item))
        } finally { changing = false }
    }
    private fun item(ep: Episode) = MediaItem.Builder().setMediaId(ep.id).setUri(Uri.fromFile(File(requireNotNull(ep.path))))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(store.library.value.feedName(ep.feedId)).setIsPlayable(true).build()).build()
    private fun persistContext(id: String) {
        preferences.edit().putString("current", id).putString("order", JSONArray(contextOrder).toString()).putInt("cursor", contextCursor).apply()
    }
    private fun saveProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val duration = player.duration
        scope.launch { safely { store.progress(id, position, duration) } }
    }
    private suspend fun saveProgressNow() {
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val duration = player.duration
        safely { store.progress(id, position, duration) }
    }
    private suspend fun safely(action: suspend () -> Unit) { try { action() } catch (e: Exception) { app.report(e) } }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        val id = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val duration = player.duration
        if (id != null) app.scope.launch { try { store.progress(id, position, duration) } catch (e: Exception) { app.report(e) } }
        store.activeEpisode.set(null)
        session?.release(); player.release(); scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val START = "com.pod4me.START"
        const val STOP = "com.pod4me.STOP"
        const val BACK10 = "com.pod4me.BACK10"
        const val FORWARD10 = "com.pod4me.FORWARD10"
        private fun decodeOrder(value: String) = runCatching {
            JSONArray(value).let { array -> (0 until array.length()).map(array::getString) }
        }.getOrDefault(emptyList())
    }
}
