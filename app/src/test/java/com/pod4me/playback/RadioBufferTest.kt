package com.pod4me.playback

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RadioBufferTest {
    private val cache get() = RuntimeEnvironment.getApplication().cacheDir
    private fun sample(extension: String) = javaClass.getResourceAsStream("/radio/silence.$extension")!!.use { it.readBytes() }
    private fun playlist(bytes: ByteArray) = HlsPlaylistParser().parse(Uri.parse("https://buffer/live.m3u8"), bytes.inputStream()) as HlsMediaPlaylist

    @Test fun mp3AndAacFramesPreserveAudioAndMeasuredDuration() {
        for (type in listOf("mp3", "aac")) {
            val original = sample(type)
            val reader = RadioFrames(original.inputStream())
            val output = ByteArrayOutputStream()
            var duration = 0L
            while (true) {
                val frame = try { reader.next() } catch (_: EOFException) { break }
                assertEquals(type, frame.extension)
                output.write(frame.bytes)
                duration += frame.durationUs
            }
            assertArrayEquals(original, output.toByteArray())
            assertTrue("Measured duration $duration", duration in 2_000_000..2_300_000)
        }
    }

    @Test fun icyMetadataIsNotWrittenIntoTheRewindAudio() {
        val original = sample("mp3")
        val encoded = ByteArrayOutputStream()
        original.asList().chunked(257).forEach { chunk ->
            encoded.write(chunk.toByteArray())
            if (chunk.size == 257) { encoded.write(1); encoded.write("StreamTitle=test".padEnd(16, '\u0000').toByteArray()) }
        }
        val audio = IcyAudioInput(encoded.toByteArray().inputStream(), 257).readBytes()
        assertArrayEquals(original, audio)
    }

    @Test fun rollingWindowKeepsThirtyMinutesAndDeletesExpiredSegments() {
        val directory = File(cache, "window-expiry")
        val window = RadioWindow(directory)
        try {
            repeat(903) { window.append(byteArrayOf(it.toByte()), 2_000_000, "mp3") }
            val media = playlist(window.read("live.m3u8"))
            assertEquals(1800_000_000L, media.durationUs)
            assertEquals(3L, media.mediaSequence)
            assertEquals(900, media.segments.size)
            assertFalse(File(directory, "0.mp3").exists())
            assertArrayEquals(byteArrayOf(3), window.read("3.mp3"))
            assertFalse(media.hasEndTag)
            try { window.read("../outside"); fail("Unknown resource") } catch (_: IOException) { }
        } finally { window.close(); directory.deleteRecursively() }
    }

    @Test fun byteLimitDiscontinuitiesAndEncryptionResourcesSurvivePlaylistRoundTrip() {
        val directory = File(cache, "window-resources")
        val window = RadioWindow(directory, maxBytes = 60)
        try {
            repeat(3) { index -> window.append(ByteArray(10), 3_000_000, "m4s", discontinuous = index == 1,
                init = ByteArray(4), key = ByteArray(16), iv = "0x0000000000000000000000000000000$index") }
            val media = playlist(window.read("live.m3u8"))
            assertEquals(1L, media.mediaSequence)
            assertEquals(2, media.segments.size)
            assertEquals(1, media.discontinuitySequence)
            assertNotNull(media.segments.first().initializationSegment)
            assertEquals(16, window.read("1.key").size)
            assertFalse(File(directory, "0.key").exists())
        } finally { window.close(); directory.deleteRecursively() }
    }

    @Test fun stopUnblocksAWaitingManifestRead() {
        val window = RadioWindow(File(cache, "window-close"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<Boolean> {
                try { window.read("live.m3u8"); false } catch (_: IOException) { true }
            }
            window.close()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }

    @Test fun hlsCaptureResolvesAudioRenditionAndMakesSegmentsSeekableLocally() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Radio\",URI=\"audio/live.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=128000,AUDIO=\"a\"\naudio/live.m3u8\n"
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:25\n#EXTINF:2.1,\none.mp3\n#EXTINF:2.1,\ntwo.mp3\n#EXTINF:2.1,\nthree.mp3\n"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            val data = when (chain.request().url.encodedPath) {
                "/master.m3u8" -> master.toByteArray()
                "/audio/live.m3u8" -> media.toByteArray()
                else -> sample("mp3")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        val buffer = RadioBuffer(cache, "https://radio.example/master.m3u8", http)
        try {
            val source = buffer.dataSourceFactory.createDataSource()
            val size = source.open(DataSpec(buffer.uri)).toInt()
            val bytes = ByteArray(size)
            assertEquals(size, source.read(bytes, 0, size))
            source.close()
            assertTrue(playlist(bytes).durationUs >= 4_200_000)
            assertTrue(requests.contains("https://radio.example/audio/one.mp3"))
            assertArrayEquals(sample("mp3"), buffer.window.read("0.mp3"))
            source.open(DataSpec.Builder().setUri(buffer.uri.buildUpon().path("/0.mp3").build()).setPosition(3).setLength(5).build())
            val part = ByteArray(5)
            assertEquals(5, source.read(part, 0, 5))
            assertArrayEquals(sample("mp3").copyOfRange(3, 8), part)
            source.close()
        } finally { buffer.close() }
    }
}
