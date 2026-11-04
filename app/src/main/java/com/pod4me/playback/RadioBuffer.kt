package com.pod4me.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.pod4me.domain.FeedParser
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Capture one broadcaster into a private thirty-minute compressed-audio window. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RadioBuffer(cacheDirectory: File, url: String,
    private val http: OkHttpClient = defaultHttp,
    capture: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val identity = UUID.randomUUID().toString()
    internal val window = RadioWindow(File(cacheDirectory, "radio-buffer-$identity"))
    val uri: Uri = Uri.parse("pod4me-radio://$identity/live.m3u8")
    private val job = scope.launch {
        try {
            if (!capture) awaitCancellation()
            // Recover private cache space left by a process kill, without touching another session.
            activeDirectories.add(window.directory.absolutePath)
            cacheDirectory.listFiles { file -> file.name.startsWith("radio-buffer-") && file.isDirectory }
                ?.filterNot { it.absolutePath in activeDirectories }?.forEach { it.deleteRecursively() }
            request(url).use { response ->
                val body = response.body ?: throw IOException("The radio returned no audio")
                val source = body.source()
                val playlist = response.header("Content-Type").orEmpty().contains("mpegurl", true) ||
                    (source.request(7) && source.peek().readUtf8(7) == "#EXTM3U")
                if (playlist) {
                    val bytes = body.byteStream().readNBytes(1024 * 1024 + 1)
                    if (bytes.size > 1024 * 1024) throw IOException("Radio playlist is too large")
                    captureHls(response.request.url.toString(), bytes)
                } else {
                    val interval = response.header("icy-metaint")?.toIntOrNull()?.takeIf { it > 0 }
                    val input = body.byteStream()
                    val frames = RadioFrames(if (interval != null) IcyAudioInput(input, interval) else input)
                    var chunk = ByteArrayOutputStream()
                    var duration = 0L
                    var extension = ""
                    while (isActive) {
                        val frame = frames.next()
                        if (extension.isNotEmpty() && extension != frame.extension) throw IOException("Radio audio format changed; reconnect to continue")
                        extension = frame.extension
                        chunk.write(frame.bytes)
                        duration += frame.durationUs
                        if (duration >= 2_000_000) {
                            window.append(chunk.toByteArray(), duration, extension)
                            chunk = ByteArrayOutputStream()
                            duration = 0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed.get() && e !is CancellationException) window.fail(IOException("Could not buffer this radio: ${e.message}", e))
        }
    }

    private fun request(url: String, offset: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): Response {
        val builder = Request.Builder().url(FeedParser.httpUrl(url)).header("Icy-MetaData", "0")
            .header("Accept-Encoding", "identity").header("User-Agent", "Pod4Me")
        if (length >= 0) builder.header("Range", "bytes=$offset-${offset + length - 1}")
        val call = http.newCall(builder.build())
        calls.add(call)
        if (closed.get()) call.cancel()
        try {
            val response = call.execute()
            if (!response.isSuccessful || (length >= 0 && response.code != 206)) {
                response.close()
                throw IOException("Radio server returned HTTP ${response.code}")
            }
            return response
        } finally {
            // Keep the open streaming call cancellable until close(); completed calls are tiny
            // but playlist polling must not accumulate them indefinitely.
            calls.removeIf { it !== call && it.isExecuted() }
        }
    }

    private fun bytes(url: String, offset: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): Pair<String, ByteArray> =
        request(url, offset, length).use { response ->
            val data = response.body?.byteStream()?.readNBytes(8 * 1024 * 1024 + 1) ?: throw IOException("Empty radio response")
            if (data.size > 8 * 1024 * 1024) throw IOException("Radio segment is too large")
            if (length >= 0 && data.size.toLong() != length) throw IOException("Incomplete radio segment")
            response.request.url.toString() to data
        }

    private suspend fun captureHls(initialUrl: String, initialBytes: ByteArray) {
        var url = initialUrl
        var data = initialBytes
        var masterDepth = 0
        var lastSequence: Long? = null
        var lastDiscontinuity: Int? = null
        var restarted = false
        while (currentCoroutineContext().isActive) {
            val playlist = HlsPlaylistParser().parse(Uri.parse(url), ByteArrayInputStream(data))
            if (playlist is HlsMultivariantPlaylist) {
                if (++masterDepth > 4) throw IOException("Radio playlists are nested too deeply")
                val next = playlist.audios.firstOrNull { it.url != null }?.url
                    ?: playlist.variants.filter { it.format.height <= 0 }.minByOrNull { it.format.bitrate }?.url
                    ?: throw IOException("This is not an audio-only radio playlist")
                val fetched = bytes(next.toString())
                url = fetched.first; data = fetched.second
                continue
            }
            val media = playlist as? HlsMediaPlaylist ?: throw IOException("Unsupported radio playlist")
            if (media.hasEndTag) throw IOException("Use a live radio playlist, not an episode playlist")
            val end = media.mediaSequence + media.segments.size - 1
            if (lastSequence != null && end < lastSequence) { lastSequence = null; lastDiscontinuity = null; restarted = true }
            val first = if (lastSequence == null) (media.segments.size - 3).coerceAtLeast(0) else 0
            for (index in first until media.segments.size) {
                val sequence = media.mediaSequence + index
                if (lastSequence != null && sequence <= lastSequence) continue
                val segment = media.segments[index]
                if (segment.hasGapTag) continue
                if (segment.drmInitData != null) throw IOException("Protected radio streams cannot be buffered")
                val address = UriUtil.resolve(url, segment.url)
                val audio = bytes(address, segment.byteRangeOffset, segment.byteRangeLength).second
                val init = segment.initializationSegment?.let { bytes(UriUtil.resolve(url, it.url), it.byteRangeOffset, it.byteRangeLength).second }
                val key = segment.fullSegmentEncryptionKeyUri?.let { bytes(UriUtil.resolve(url, it)).second }
                if (key != null && key.size != 16) throw IOException("Invalid radio encryption key")
                val discontinuity = media.discontinuitySequence + segment.relativeDiscontinuitySequence
                val gap = lastSequence != null && sequence != lastSequence + 1
                val extension = when {
                    init != null -> "m4s"
                    Uri.parse(address).path.orEmpty().endsWith(".aac", true) -> "aac"
                    Uri.parse(address).path.orEmpty().endsWith(".mp3", true) -> "mp3"
                    else -> "ts"
                }
                window.append(audio, segment.durationUs, extension,
                    discontinuous = restarted || gap || (lastDiscontinuity != null && lastDiscontinuity != discontinuity),
                    init = init, key = key, iv = segment.encryptionIV)
                lastSequence = sequence
                lastDiscontinuity = discontinuity
                restarted = false
            }
            delay((media.targetDurationUs / 2000).coerceIn(500, 5000))
            val fetched = bytes(url)
            url = fetched.first; data = fetched.second
        }
    }

    val dataSourceFactory = DataSource.Factory {
        object : BaseDataSource(false) {
            private var input: ByteArrayInputStream? = null
            private var openedUri: Uri? = null
            override fun open(dataSpec: DataSpec): Long {
                transferInitializing(dataSpec)
                if (dataSpec.uri.scheme != this@RadioBuffer.uri.scheme || dataSpec.uri.authority != identity) throw IOException("Unknown radio buffer")
                val data = window.read(dataSpec.uri.lastPathSegment.orEmpty())
                if (dataSpec.position > data.size) throw IOException("Radio buffer position unavailable")
                val count = minOf(data.size.toLong() - dataSpec.position,
                    dataSpec.length.takeIf { it >= 0 } ?: Long.MAX_VALUE).toInt()
                input = ByteArrayInputStream(data, dataSpec.position.toInt(), count)
                openedUri = dataSpec.uri
                transferStarted(dataSpec)
                return count.toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val count = input?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                if (count > 0) bytesTransferred(count)
                return count
            }
            override fun getUri(): Uri? = openedUri
            override fun close() {
                if (input != null) { input = null; openedUri = null; transferEnded() }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        window.close()
        calls.forEach(Call::cancel)
        job.cancel()
        scope.launch {
            job.join()
            window.directory.deleteRecursively()
            activeDirectories.remove(window.directory.absolutePath)
            scope.cancel()
        }
    }

    companion object {
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()
        private val defaultHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).build()
    }
}

/** Keep the ordinary podcast/video source unchanged; only private radio URIs use the cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RadioDataSource(private val fallback: DataSource.Factory, private val buffer: () -> RadioBuffer?) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "pod4me-radio")
            buffer()?.dataSourceFactory?.createDataSource() ?: throw IOException("Radio buffer unavailable")
        else fallback.createDataSource()
        delegate = source
        listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = requireNotNull(delegate).read(bytes, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { delegate?.close(); delegate = null }
}
