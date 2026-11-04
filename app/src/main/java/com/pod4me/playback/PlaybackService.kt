package com.pod4me.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.datasource.DefaultHttpDataSource
import com.pod4me.BuildConfig
import androidx.media3.common.*
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.pod4me.MainActivity
import com.pod4me.PodApplication
import com.pod4me.domain.Episode
import com.pod4me.domain.Library
import com.pod4me.domain.Policy
import com.pod4me.domain.Upcoming
import com.pod4me.domain.RadioStation
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
    private var allowSkipOnError = false
    private val failedItems = mutableSetOf<String>()
    private var radioStatusJob: Job? = null
    private var sleepDeadline: Long? = null
    private var sleepJob: Job? = null
    private data class Extras(val radioStatus: String?, val hasAudio: Boolean, val notice: String?, val deadline: Long?, val episodeEnd: Boolean)
    private var publishedExtras: Extras? = null
    private var sleepAtEpisodeEnd = false
    private var radioNotice: String? = null
    private val pauseWakeLock by lazy { getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pod4Me:PausedRadio") }
    private var streamingVideo = false
    private var liveStation: RadioStation? = null
    @Volatile private var radioBuffer: RadioBuffer? = null
    internal var createRadioBuffer: (RadioStation) -> RadioBuffer = { RadioBuffer(cacheDir, it.url) }

    override fun onCreate() {
        super.onCreate()
        val fallback = DefaultDataSource.Factory(this, videoHttpFactory())
        val sources = DefaultMediaSourceFactory(this).setDataSourceFactory(DataSource.Factory { RadioDataSource(fallback) { radioBuffer } })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.setPlaybackSpeed(preferences.getFloat("speed", 1f).coerceIn(1f, 3f))
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (changing) return
                val oldId = oldPosition.mediaItem?.mediaId
                val newId = newPosition.mediaItem?.mediaId
                // A seek must never persist the position that the user just left.
                if (oldId != null && oldId != newId && reason in setOf(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, Player.DISCONTINUITY_REASON_SEEK)) {
                    val timeline = player.currentTimeline
                    val measured = if (oldPosition.mediaItemIndex in 0 until timeline.windowCount)
                        timeline.getWindow(oldPosition.mediaItemIndex, Timeline.Window()).durationMs else 0L
                    queueProgress(oldId, oldPosition.positionMs, measured)
                }
                if (newId != null && oldId == newId && reason == Player.DISCONTINUITY_REASON_SEEK)
                    queueProgress(newId, newPosition.positionMs, player.duration)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (changing || mediaItem == null || RadioStation.isRadio(mediaItem.mediaId)) return
                val id = mediaItem.mediaId
                allowSkipOnError = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                store.activeEpisode.set(id)
                val index = contextOrder.indexOf(id)
                if (index >= 0) contextCursor = index
                persistContext(id)
                val ep = store.library.value.episode(id)
                if (ep != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    changing = true
                    try { player.seekTo(Policy.resumePosition(ep.position, ep.duration)) }
                    finally { changing = false }
                }
                scope.launch { safely { operations.withLock { store.removeFromQueue(id); rebuildUpcoming() } } }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // A deliberate resume after sleeping past a paused timer starts normally.
                if (playWhenReady) clearExpiredTimerOnResume()
                if (sleepAtEpisodeEnd && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    cancelSleepTimer()
                }
                if (!changing && liveStation != null && playWhenReady && player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                if (!changing) updateRadioPause()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!changing) saveProgress()
                if (isPlaying && liveStation == null && !streamingVideo) {
                    player.currentMediaItem?.mediaId?.let { id -> scope.launch { safely { store.clearPlaybackError(id) } } }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress()
                    if (sleepAtEpisodeEnd) { player.pause(); cancelSleepTimer() }
                    if (liveStation != null) player.pause()
                }
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (liveStation == null && !changing) preferences.edit().putFloat("speed", playbackParameters.speed).apply()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (liveStation != null && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Pausing longer than the retained window resumes at the oldest available audio.
                    player.seekTo(0)
                    player.prepare()
                    radioNotice = "The paused position expired. Playing the oldest retained audio."
                    publishExtras()
                } else {
                    val id = player.currentMediaItem?.mediaId
                    scope.launch {
                        if (liveStation != null && error.errorCode in 2000..2999) delay(1000)
                        operations.withLock {
                            if (player.currentMediaItem?.mediaId == id && player.playerError != null) recoverPlaybackError(error.errorCode)
                        }
                    }
                }
            }
        })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands(controller)).setAvailablePlayerCommands(playerCommands()).build()
                }
                override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    scope.launch {
                        try {
                            operations.withLock {
                                store.initialize()
                                val station = savedRadio()
                                if (station != null) {
                                    replaceRadioBuffer(station)
                                    streamingVideo = false
                                    liveStation = station
                                    started = true
                                    store.activeEpisode.set(null)
                                    setRadioAudio(true)
                                    player.setWakeMode(C.WAKE_MODE_NETWORK)
                                    player.setPlaybackSpeed(1f)
                                    updateControls()
                                    result.set(MediaSession.MediaItemsWithStartPosition(listOf(radioItem(station)), 0, C.TIME_UNSET))
                                    return@withLock
                                }
                                val (id, order) = resumeSelection()
                                val ep = store.activate(id)
                                setRadioAudio(false)
                                player.setWakeMode(C.WAKE_MODE_LOCAL)
                                failedItems.clear()
                                allowSkipOnError = false
                                player.setPlaybackSpeed(preferences.getFloat("speed", 1f).coerceIn(1f, 3f))
                                contextOrder = order
                                contextCursor = contextOrder.indexOf(id).takeIf { it >= 0 } ?: preferences.getInt("cursor", -1)
                                started = true
                                val before = preceding(id)
                                val available = availableIds(store.library.value)
                                val next = Upcoming.plan(store.library.value.queue, contextOrder, contextCursor, id, before.map(Episode::id).toSet(), available)
                                    .mapNotNull(store.library.value::episode)
                                result.set(MediaSession.MediaItemsWithStartPosition((before + ep + next).map(::item), before.size,
                                    Policy.resumePosition(ep.position, ep.duration)))
                            }
                        } catch (e: Exception) { app.report(e); result.setException(e) }
                    }
                    return result
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
                                SLEEP_TIMER -> setSleepTimer(args.getLong("duration"))
                                GO_LIVE -> goLive()
                                START_RADIO -> startRadio(requireNotNull(args.getString("id")))
                                STREAM_VIDEO -> startVideoStream(requireNotNull(args.getString("id")))
                                MARK_UNHEARD -> {
                                    val id = requireNotNull(args.getString("id")) { "Episode is missing" }
                                    markUnheard(id)
                                }
                                TOGGLE -> togglePlayback()
                                PLAY_QUEUE -> startQueue()
                                STOP -> stopPlayback()
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
            }).setMediaButtonPreferences(podcastButtons()).build()
        scope.launch {
            store.library.map { library -> library.queue to library.episodes.filter { it.isDownloaded }.map { it.id } }
                .distinctUntilChanged().collect { operations.withLock { if (started) rebuildUpcoming() } }
        }
        scope.launch {
            while (isActive) { delay(5_000); if (player.isPlaying) operations.withLock { saveProgressNow() } }
        }
        // Keep a cold session unloaded. Resumption loads the saved item only on Play,
        // so simply opening the app cannot protect an expired file from deletion.
    }

    /** Duration -1 cancels, 0 means episode end, positive values are elapsed milliseconds. */
    internal fun setSleepTimer(durationMs: Long) {
        require(durationMs in -1..120 * 60_000L) { "Choose a timer up to 120 minutes" }
        if (durationMs == -1L) { cancelSleepTimer(); return }
        require(player.currentMediaItem != null) { "Start an episode or radio first" }
        require(durationMs != 0L || liveStation == null) { "Live radio has no episode end" }
        sleepJob?.cancel(); sleepJob = null
        sleepAtEpisodeEnd = durationMs == 0L
        sleepDeadline = durationMs.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() + it }
        player.pauseAtEndOfMediaItems = sleepAtEpisodeEnd
        sleepDeadline?.let { deadline -> sleepJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            safely { operations.withLock { sleepJob = null; checkSleepTimer() } }
        } }
        publishExtras()
    }
    private fun cancelSleepTimer() {
        sleepJob?.cancel(); sleepJob = null
        sleepDeadline = null; sleepAtEpisodeEnd = false
        player.pauseAtEndOfMediaItems = false
        publishExtras()
    }
    internal suspend fun checkSleepTimer(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (sleepDeadline?.let { now >= it } != true) return false
        cancelSleepTimer()
        if (liveStation != null) stopPlayback()
        else { player.pause(); saveProgressNow() }
        return true
    }
    private fun clearExpiredTimerOnResume() {
        if (sleepDeadline?.let { SystemClock.elapsedRealtime() >= it } == true) cancelSleepTimer()
    }
    private fun publishExtras() {
        val current = Extras(radioBuffer?.status?.value?.name, radioBuffer?.window?.hasAudio == true,
            radioNotice, sleepDeadline, sleepAtEpisodeEnd)
        val activeSession = session ?: return
        if (current == publishedExtras) return
        publishedExtras = current
        activeSession.setSessionExtras(Bundle().apply {
            putString(RADIO_STATUS, current.radioStatus); putBoolean(RADIO_HAS_AUDIO, current.hasAudio)
            putString(RADIO_NOTICE, current.notice)
            putBoolean(SLEEP_END, current.episodeEnd)
            putLong(SLEEP_DEADLINE, current.deadline ?: -1L)
        })
    }

    internal suspend fun recoverPlaybackError(code: Int) {
        if (liveStation != null && code in 2000..2999) {
            // Playlist refresh errors during an outage do not destroy the independent recorder.
            // Re-prepare the same local source/position, including a finished retained window.
            if (radioBuffer?.window?.hasAudio == true || radioBuffer?.status?.value == RadioBuffer.Status.CAPTURING ||
                radioBuffer?.status?.value == RadioBuffer.Status.RECONNECTING) player.prepare()
            else handlePlaybackError(radioFailureMessage())
            return
        }
        val id = player.currentMediaItem?.mediaId
        if (id != null && liveStation == null && !streamingVideo && code in 2000..4999) {
            store.playbackError(id, "This download could not be played. Delete it and download it again.")
            failedItems.add(id)
            if (allowSkipOnError && code in 2000..4999) {
                val library = store.library.value
                val available = availableIds(library)
                val next = Upcoming.plan(library.queue, contextOrder, contextCursor, id, failedItems, available)
                    .firstOrNull()
                if (next != null) {
                    val play = player.playWhenReady
                    startEpisode(next, contextOrder, -1, play, continuing = true)
                    return
                }
            }
        }
        handlePlaybackError(when {
            code in 5000..5999 -> "Audio output was interrupted. Check your speaker or Bluetooth connection and tap Play to retry."
            code !in 2000..4999 -> "Playback stopped unexpectedly. Tap Play to retry."
            else -> null
        })
    }

    private fun setRadioAudio(radio: Boolean) {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (radio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
    }

    private fun updateRadioPause() {
        val buffer = radioBuffer
        val paused = !player.playWhenReady
        buffer?.setPaused(paused)
        val recording = buffer?.status?.value in setOf(RadioBuffer.Status.CAPTURING, RadioBuffer.Status.RECONNECTING)
        if (buffer != null && paused && recording) {
            if (!pauseWakeLock.isHeld) pauseWakeLock.acquire(30 * 60_000L)
        } else if (pauseWakeLock.isHeld) pauseWakeLock.release()
    }

    internal suspend fun goLive() {
        val station = liveStation ?: return
        radioNotice = null
        if (radioBuffer?.status?.value in setOf(RadioBuffer.Status.PAUSE_LIMIT, RadioBuffer.Status.UNAVAILABLE)) {
            startRadio(station.id)
        } else { player.seekToDefaultPosition(); player.play() }
        publishExtras()
    }

    private fun radioFailureMessage(): String? = radioBuffer?.lastFailure?.let {
        radioStartupMessage(liveStation?.name ?: "this radio", it)
    }
    internal fun handlePlaybackError(message: String? = null) {
        val failedStation = liveStation
        val failedStream = streamingVideo
        saveProgress()
        changing = true
        try {
            player.stop()
            player.clearMediaItems()
            started = false
            liveStation = null
            streamingVideo = false
            closeRadioBuffer()
            store.activeEpisode.set(null)
        } finally { changing = false }
        updateControls()
        app.report(IllegalStateException(message ?: if (failedStation != null)
            "Could not play ${failedStation.name}. Check your connection or the direct stream URL, then tap Play to reconnect."
            else if (failedStream) "The video stream could not be played. Check your connection or try downloading the episode."
            else "The downloaded file could not be played. Playback has stopped. You can delete it from Library, try another episode, or retry later."))
    }

    internal suspend fun savedRadio(): RadioStation? {
        val id = preferences.getString("radio", null) ?: return null
        return app.radios.read().firstOrNull { it.id == id }
    }

    internal suspend fun startRadio(id: String, play: Boolean = true) {
        val station = requireNotNull(app.radios.read().firstOrNull { it.id == id }) { "This radio is no longer subscribed." }
        saveProgressNow()
        if (sleepAtEpisodeEnd) cancelSleepTimer()
        radioNotice = null
        changing = true
        try {
            player.stop()
            streamingVideo = false
            liveStation = station
            replaceRadioBuffer(station)
            store.activeEpisode.set(null)
            started = true
            setRadioAudio(true)
            player.setWakeMode(C.WAKE_MODE_NETWORK)
            player.setPlaybackSpeed(1f)
            player.setMediaItem(radioItem(station))
            player.prepare()
            player.playWhenReady = play
        } finally { changing = false }
        updateRadioPause()
        preferences.edit().putString("radio", station.id).apply()
        updateControls()
    }

    internal suspend fun stopPlayback() {
        cancelSleepTimer()
        saveProgressNow()
        val wasRadio = liveStation != null || streamingVideo
        changing = true
        try {
            player.stop(); player.clearMediaItems()
            player.setWakeMode(C.WAKE_MODE_LOCAL)
            setRadioAudio(false)
            liveStation = null
            streamingVideo = false
            closeRadioBuffer()
            started = false
            store.activeEpisode.set(null)
        } finally { changing = false }
        val editor = preferences.edit().remove("radio")
        if (!wasRadio) editor.remove("current")
        editor.apply()
        updateControls()
    }

    private fun closeRadioBuffer() {
        radioStatusJob?.cancel(); radioStatusJob = null
        radioBuffer?.close(); radioBuffer = null
        if (pauseWakeLock.isHeld) pauseWakeLock.release()
        radioNotice = null
        publishExtras()
    }
    private fun replaceRadioBuffer(station: RadioStation) {
        closeRadioBuffer()
        val buffer = createRadioBuffer(station)
        radioBuffer = buffer
        radioStatusJob = scope.launch {
            // An immediate HTTP failure must not tear down a player that is still being configured.
            yield()
            buffer.status.collect { status -> safely { operations.withLock {
                if (radioBuffer !== buffer) return@withLock
                radioNotice = null
                publishExtras()
                if (status == RadioBuffer.Status.UNAVAILABLE && !buffer.window.hasAudio && liveStation?.id == station.id) {
                    handlePlaybackError(radioFailureMessage())
                } else updateRadioPause()
            } } }
        }
    }
    private fun radioItem(station: RadioStation) = MediaItem.Builder().setMediaId(station.id).setUri(requireNotNull(radioBuffer).uri)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(4000)
            .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtist("Live radio").setIsPlayable(true).build()).build()

    private fun sessionCommands(controller: MediaSession.ControllerInfo): SessionCommands {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        commands.add(SessionCommand(BACK10, Bundle.EMPTY)).add(SessionCommand(FORWARD10, Bundle.EMPTY))
        if (controller.packageName == packageName) listOf(START, START_RADIO, GO_LIVE, SLEEP_TIMER, STREAM_VIDEO, STOP, MARK_UNHEARD, TOGGLE, PLAY_QUEUE).forEach {
            commands.add(SessionCommand(it, Bundle.EMPTY))
        }
        return commands.build()
    }

    internal fun playerCommands(): Player.Commands {
        val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        if (liveStation != null) listOf(Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SET_SPEED_AND_PITCH).forEach(commands::remove)
        return commands.build()
    }

    private fun podcastButtons() = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setDisplayName("Back 10 seconds")
            .setSessionCommand(SessionCommand(BACK10, Bundle.EMPTY)).setSlots(CommandButton.SLOT_BACK_SECONDARY).build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setDisplayName("Forward 10 seconds")
            .setSessionCommand(SessionCommand(FORWARD10, Bundle.EMPTY)).setSlots(CommandButton.SLOT_FORWARD_SECONDARY).build(),
    )

    private fun updateControls() {
        val current = session ?: return
        current.connectedControllers.forEach { current.setAvailableCommands(it, sessionCommands(it), playerCommands()) }
        current.setMediaButtonPreferences(podcastButtons())
    }

    internal suspend fun markUnheard(id: String) {
        changing = true
        try {
            if (player.currentMediaItem?.mediaId == id) player.seekTo(0)
        } finally { changing = false }
        store.membership(id, "unheard", true)
    }

    /** Queue entries can survive without files after restore; choose a real file, never an empty timeline. */
    internal suspend fun resumeSelection(queueOnly: Boolean = false): Pair<String, List<String>> {
        if (!store.library.value.ready) store.initialize()
        val library = store.library.value
        val queued = withContext(Dispatchers.IO) {
            library.queue.firstOrNull { id -> library.episode(id)?.path?.let { File(it).isFile && File(it).length() > 0 } == true }
        }
        if (queued != null) return queued to library.queue
        if (!queueOnly) {
            val saved = preferences.getString("current", null)
            val available = withContext(Dispatchers.IO) {
                library.episode(saved)?.path?.let { File(it).isFile && File(it).length() > 0 } == true
            }
            if (saved != null && available) return saved to decodeOrder(preferences.getString("order", "[]") ?: "[]")
        }
        error(if (library.queue.isNotEmpty()) "Queued audio is missing. Open Queue and download an episode to play it."
            else "Choose a downloaded episode or add one to Queue first.")
    }

    internal suspend fun startQueue(play: Boolean = true) {
        failedItems.clear()
        val (id, order) = resumeSelection(queueOnly = true)
        startEpisode(id, order, -1, play, continuing = true)
    }

    internal suspend fun togglePlayback() {
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) clearExpiredTimerOnResume()
        if (checkSleepTimer()) return
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null) {
            player.pause()
            return
        }
        if (liveStation != null) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            return
        }
        if (player.currentMediaItem == null) {
            savedRadio()?.let { startRadio(it.id); return }
            val (id, order) = resumeSelection()
            startEpisode(id, order, -1)
            return
        }
        if (!streamingVideo && player.playbackState == Player.STATE_ENDED && store.library.value.queue.isNotEmpty()) {
            val selection = runCatching { resumeSelection(queueOnly = true) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
            if (selection != null) { startEpisode(selection.first, selection.second, -1); return }
        }
        // Revalidate before retrying a stopped/error player, rather than silently retrying a vanished file.
        store.activate(requireNotNull(player.currentMediaItem).mediaId, stream = streamingVideo)
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    internal suspend fun startVideoStream(id: String, play: Boolean = true) {
        saveProgressNow()
        val ep = store.activate(id, stream = true)
        changing = true
        try {
            player.stop()
            liveStation = null
            closeRadioBuffer()
            streamingVideo = true
            started = true
            preferences.edit().remove("radio").apply()
            setRadioAudio(false)
            player.setWakeMode(C.WAKE_MODE_NETWORK)
            player.setPlaybackSpeed(preferences.getFloat("speed", 1f).coerceIn(1f, 3f))
            player.setMediaItem(MediaItem.Builder().setMediaId(ep.id).setUri(ep.audioUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title)
                    .setArtist(store.library.value.feedName(ep.feedId)).setIsPlayable(true).build()).build(),
                Policy.resumePosition(ep.position, ep.duration))
            player.prepare()
            player.playWhenReady = play
        } finally { changing = false }
        // Streaming is explicit and one episode at a time; leave the downloaded queue/context intact.
        updateControls()
    }

    internal suspend fun startEpisode(id: String, order: List<String>, position: Long, play: Boolean = true, continuing: Boolean = false) {
        saveProgressNow()
        val ep = store.activate(id)
        if (!continuing) failedItems.clear()
        allowSkipOnError = continuing
        streamingVideo = false
        changing = true
        try {
            player.pause()
            liveStation = null
            streamingVideo = false
            closeRadioBuffer()
            preferences.edit().remove("radio").apply()
            setRadioAudio(false)
            player.setWakeMode(C.WAKE_MODE_LOCAL)
            player.setPlaybackSpeed(preferences.getFloat("speed", 1f).coerceIn(1f, 3f))
            started = true
            contextOrder = order.distinct()
            contextCursor = contextOrder.indexOf(id).takeIf { it >= 0 } ?: if (play) -1 else contextCursor
            val before = preceding(id)
            player.setMediaItems((before + ep).map(::item), before.size,
                if (position >= 0) position else Policy.resumePosition(ep.position, ep.duration))
            player.prepare()
        } finally { changing = false }
        persistContext(id)
        updateControls()
        store.removeFromQueue(id)
        rebuildUpcoming()
        if (play) player.play()
    }

    private suspend fun availableIds(library: Library): Set<String> = withContext(Dispatchers.IO) {
        library.episodes.filter { ep -> ep.id !in failedItems && ep.path?.let { File(it).isFile && File(it).length() > 0 } == true }.map(Episode::id).toSet()
    }

    private suspend fun preceding(id: String): List<Episode> {
        val library = store.library.value
        val available = availableIds(library)
        return contextOrder.take(contextOrder.indexOf(id).coerceAtLeast(0))
            .filter { it in available }.mapNotNull(library::episode).takeLast(5)
    }

    private suspend fun rebuildUpcoming() {
        if (liveStation != null || streamingVideo) return
        val id = player.currentMediaItem?.mediaId ?: return
        val library = store.library.value
        val available = availableIds(library)
        if (player.currentMediaItem?.mediaId != id) return
        // Previous items can expire or be deleted too; remove them without reloading the current item.
        changing = true
        try {
            for (index in player.currentMediaItemIndex - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId !in available) player.removeMediaItem(index)
            }
        } finally { changing = false }
        val history = (0..player.currentMediaItemIndex).map { player.getMediaItemAt(it).mediaId }.toSet()
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
        .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(store.library.value.feedName(ep.feedId)).setArtworkUri(store.library.value.feedArtwork(ep.feedId)?.let(Uri::parse)).setIsPlayable(true).build()).build()
    private fun persistContext(id: String) {
        preferences.edit().putString("current", id).putString("order", JSONArray(contextOrder).toString()).putInt("cursor", contextCursor).apply()
    }
    private fun saveProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val duration = player.duration
        queueProgress(id, position, duration)
    }
    private fun queueProgress(id: String, position: Long, duration: Long) {
        if (RadioStation.isRadio(id)) return
        val epoch = store.progressEpoch(id)
        scope.launch { safely { store.progress(id, position, duration, epoch) } }
    }
    private suspend fun saveProgressNow() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (RadioStation.isRadio(id)) return
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
        val epoch = id?.let(store::progressEpoch) ?: 0L
        if (id != null && !RadioStation.isRadio(id)) app.scope.launch { try { store.progress(id, position, duration, epoch) } catch (e: Exception) { app.report(e) } }
        store.activeEpisode.set(null)
        closeRadioBuffer()
        session?.release(); player.release(); scope.cancel()
        super.onDestroy()
    }
    companion object {
        internal fun videoHttpFactory() = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setUserAgent("Pod4Me/${BuildConfig.VERSION_NAME}")
        const val SLEEP_TIMER = "com.pod4me.SLEEP_TIMER"
        const val SLEEP_DEADLINE = "sleep_deadline"
        const val SLEEP_END = "sleep_end"
        const val RADIO_HAS_AUDIO = "radio_has_audio"
        const val RADIO_NOTICE = "radio_notice"
        const val RADIO_STATUS = "radio_status"
        const val GO_LIVE = "com.pod4me.GO_LIVE"
        const val START = "com.pod4me.START"
        const val STREAM_VIDEO = "com.pod4me.STREAM_VIDEO"
        const val START_RADIO = "com.pod4me.START_RADIO"
        const val MARK_UNHEARD = "com.pod4me.MARK_UNHEARD"
        const val STOP = "com.pod4me.STOP"
        const val TOGGLE = "com.pod4me.TOGGLE"
        const val PLAY_QUEUE = "com.pod4me.PLAY_QUEUE"
        const val BACK10 = "com.pod4me.BACK10"
        const val FORWARD10 = "com.pod4me.FORWARD10"
        private fun decodeOrder(value: String) = runCatching {
            JSONArray(value).let { array -> (0 until array.length()).map(array::getString) }
        }.getOrDefault(emptyList())
    }
}
