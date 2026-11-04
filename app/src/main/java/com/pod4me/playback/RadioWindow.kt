package com.pod4me.playback

import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

/** Private, bounded compressed-audio segments exposed as a seekable sliding HLS window. */
internal class RadioWindow(
    val directory: File,
    private val maxDurationUs: Long = 30 * 60 * 1_000_000L,
    private val maxBytes: Long = 128L * 1024 * 1024,
) {
    private data class Segment(val sequence: Long, val durationUs: Long, val name: String,
        val discontinuity: Int, val files: Set<String>, val iv: String?)
    private val lock = Object()
    private val segments = ArrayDeque<Segment>()
    private var sequence = 0L
    private var discontinuity = 0
    private var durationUs = 0L
    private var byteCount = 0L
    private var targetSeconds = 1
    private var closed = false
    private var failure: IOException? = null
    private var finished = false
    val hasAudio: Boolean get() = synchronized(lock) { segments.isNotEmpty() }
    val latestSequence: Long get() = synchronized(lock) { sequence }

    fun append(audio: ByteArray, duration: Long, extension: String, discontinuous: Boolean = false,
        init: ByteArray? = null, key: ByteArray? = null, iv: String? = null) = synchronized(lock) {
        if (closed || finished) return@synchronized
        require(duration in 1..120_000_000 && extension in setOf("mp3", "aac", "ts", "m4s"))
        if (discontinuous) discontinuity++
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the radio buffer" }
        val index = sequence++
        val name = "$index.$extension"
        val files = linkedMapOf(name to audio)
        init?.let { files["$index.init"] = it }
        key?.let { files["$index.key"] = it }
        files.forEach { (file, bytes) -> File(directory, file).writeBytes(bytes) }
        // Keep only small resource descriptors in memory; media itself lives in the cache.
        segments.addLast(Segment(index, duration, name, discontinuity, files.keys.toSet(), iv))
        durationUs += duration
        byteCount += files.values.sumOf { it.size.toLong() }
        targetSeconds = maxOf(targetSeconds, ceil(duration / 1_000_000.0).toInt())
        while (segments.size > 2 && (durationUs > maxDurationUs || byteCount > maxBytes)) {
            val removed = segments.removeFirst()
            durationUs -= removed.durationUs
            removed.files.forEach { file ->
                val path = File(directory, file)
                byteCount -= path.length()
                check(!path.exists() || path.delete()) { "Could not expire the radio buffer" }
            }
        }
        lock.notifyAll()
    }

    fun fail(error: IOException) = synchronized(lock) { failure = error; lock.notifyAll() }
    fun finish() = synchronized(lock) { finished = true; lock.notifyAll() }
    fun close() = synchronized(lock) { closed = true; lock.notifyAll() }

    fun read(name: String): ByteArray = synchronized(lock) {
        if (name == "live.m3u8") {
            val deadline = System.nanoTime() + 35_000_000_000L
            while (segments.size < 2 && !closed && !finished && failure == null) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) throw IOException("The station did not provide audio in time")
                lock.wait(remaining.coerceAtLeast(1))
            }
        }
        if (closed) throw IOException("Radio buffer closed")
        // An upstream outage must never invalidate audio already captured locally.
        if (segments.isEmpty()) throw failure ?: IOException("No radio audio has been received yet")
        if (name != "live.m3u8") {
            if (segments.none { name in it.files }) throw IOException("This part of the radio buffer has expired")
            return@synchronized File(directory, name).readBytes()
        }
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:$targetSeconds\n")
            append("#EXT-X-MEDIA-SEQUENCE:${segments.first().sequence}\n")
            append("#EXT-X-DISCONTINUITY-SEQUENCE:${segments.first().discontinuity}\n")
            var previous = segments.first().discontinuity
            for (segment in segments) {
                if (segment.discontinuity != previous) append("#EXT-X-DISCONTINUITY\n")
                previous = segment.discontinuity
                val index = segment.sequence
                if ("$index.key" in segment.files) append("#EXT-X-KEY:METHOD=AES-128,URI=\"$index.key\",IV=${segment.iv}\n")
                else append("#EXT-X-KEY:METHOD=NONE\n")
                if ("$index.init" in segment.files) append("#EXT-X-MAP:URI=\"$index.init\"\n")
                append(String.format(Locale.US, "#EXTINF:%.6f,\n", segment.durationUs / 1_000_000.0))
                append(segment.name).append('\n')
            }
            if (finished) append("#EXT-X-ENDLIST\n")
        }.toByteArray()
    }
}
