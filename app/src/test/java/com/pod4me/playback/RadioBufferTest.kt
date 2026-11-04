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

    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Condition timed out", check())
    }

    @Test fun invalidStationStopsImmediatelyAndTransientStartupStopsAfterThreeAttempts() {
        for (kind in listOf("404", "html", "offline")) {
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                if (kind == "offline") throw IOException("Offline")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (kind == "404") 404 else 200).message("Test")
                    .body("<html><body>Station home page</body></html>".toResponseBody()).build()
            }.build()
            val buffer = RadioBuffer(cache, "https://radio.example/live", http, retryDelayMs = 1)
            try {
                awaitCondition { buffer.status.value == RadioBuffer.Status.UNAVAILABLE }
                assertFalse(buffer.window.hasAudio)
                assertEquals(if (kind == "offline") 3 else 1, requests.get())
            } finally { buffer.close() }
        }
    }

    @Test fun temporaryClientErrorsAndPortalPagesAfterAudioReconnect() {
        for (failure in listOf("404", "403", "html")) {
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val failed = requests.incrementAndGet() == 2
                val data = if (failed && failure == "html") "<html>Sign in to Wi-Fi</html>".toByteArray() else sample("mp3") + sample("mp3")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (failed && failure != "html") failure.toInt() else 200).message("Test").body(data.toResponseBody()).build()
            }.build()
            val buffer = RadioBuffer(cache, "https://radio.example/live", http, retryDelayMs = 10)
            try {
                awaitCondition { buffer.window.latestSequence >= 4 }
                assertTrue(requests.get() >= 3)
                assertNotEquals(RadioBuffer.Status.UNAVAILABLE, buffer.status.value)
                assertTrue(buffer.window.read("0.mp3").isNotEmpty())
                assertTrue(playlist(buffer.window.read("live.m3u8")).segments.any { it.relativeDiscontinuitySequence > 0 })
            } finally { buffer.close() }
        }
    }

    @Test fun missingHlsSegmentAfterAudioRefetchesPlaylistAndRecovers() {
        val segmentRequests = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val failed = path == "/12.mp3" && segmentRequests.incrementAndGet() == 1
            val data = if (path.endsWith("m3u8"))
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:2.1,\n10.mp3\n#EXTINF:2.1,\n11.mp3\n#EXTINF:2.1,\n12.mp3\n".toByteArray()
                else sample("mp3")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (failed) 404 else 200)
                .message("Test").body(data.toResponseBody()).build()
        }.build()
        val buffer = RadioBuffer(cache, "https://radio.example/live.m3u8", http, retryDelayMs = 10)
        try {
            awaitCondition { buffer.window.latestSequence == 3L }
            assertEquals(2, segmentRequests.get())
            assertNotEquals(RadioBuffer.Status.UNAVAILABLE, buffer.status.value)
            assertEquals(3, playlist(buffer.window.read("live.m3u8")).segments.size)
        } finally { buffer.close() }
    }

    @Test fun upstreamFailureKeepsSegmentsAndReconnectAddsDiscontinuity() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val audio = sample("mp3") + sample("mp3")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 2) throw IOException("Network dropped")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(audio.toResponseBody()).build()
        }.build()
        val buffer = RadioBuffer(cache, "https://radio.example/live", http, retryDelayMs = 20)
        try {
            awaitCondition { buffer.window.latestSequence >= 4 }
            assertTrue(buffer.window.read("0.mp3").isNotEmpty())
            val media = playlist(buffer.window.read("live.m3u8"))
            assertTrue(media.segments.any { it.relativeDiscontinuitySequence > 0 })
            assertTrue(requests.get() >= 3)
        } finally { buffer.close() }
    }

    @Test fun pausedOutageRecoversAndPauseLimitKeepsPlayableAudioWithoutRestartingCapture() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) throw IOException("Offline while paused")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((sample("mp3") + sample("mp3")).toResponseBody()).build()
        }.build()
        val buffer = RadioBuffer(cache, "https://radio.example/live", http, retryDelayMs = 20, pauseLimitMs = 300)
        try {
            buffer.setPaused(true)
            awaitCondition { buffer.status.value == RadioBuffer.Status.PAUSE_LIMIT }
            assertTrue(buffer.window.hasAudio)
            assertTrue(playlist(buffer.window.read("live.m3u8")).hasEndTag)
            val sequence = buffer.window.latestSequence
            val count = requests.get()
            buffer.setPaused(false)
            Thread.sleep(100)
            assertEquals(sequence, buffer.window.latestSequence)
            assertEquals(count, requests.get())
            assertTrue(buffer.window.read("0.mp3").isNotEmpty())
        } finally { buffer.close() }
    }

    @Test fun retryExhaustionDoesNotDestroyPreviouslyRecordedAudio() {
        val http = OkHttpClient.Builder().addInterceptor { throw IOException("Offline") }.build()
        val buffer = RadioBuffer(cache, "https://radio.example/live", http, retryDelayMs = 10, retryLimit = 2)
        try {
            repeat(2) { buffer.window.append(sample("mp3"), 2_100_000, "mp3") }
            awaitCondition { buffer.status.value == RadioBuffer.Status.UNAVAILABLE }
            assertTrue(playlist(buffer.window.read("live.m3u8")).hasEndTag)
            assertArrayEquals(sample("mp3"), buffer.window.read("0.mp3"))
        } finally { buffer.close() }
    }

    @Test fun failedWindowStillServesExistingSegments() {
        val window = RadioWindow(File(cache, "failed-window"))
        try {
            repeat(2) { window.append(sample("mp3"), 2_100_000, "mp3") }
            window.fail(IOException("Upstream disconnected"))
            assertArrayEquals(sample("mp3"), window.read("0.mp3"))
            assertEquals(2, playlist(window.read("live.m3u8")).segments.size)
        } finally { window.close(); window.directory.deleteRecursively() }
    }

    @Test fun failedHlsPollRecoversWithoutDuplicatingAlreadyCapturedSegments() {
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val data = if (chain.request().url.encodedPath.endsWith("m3u8")) {
                val poll = polls.incrementAndGet()
                if (poll == 2) throw IOException("One lost playlist poll")
                val seq = if (poll == 1) 10 else 11
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$seq\n#EXTINF:2.1,\n$seq.mp3\n#EXTINF:2.1,\n${seq + 1}.mp3\n".toByteArray()
            } else sample("mp3")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody()).build()
        }.build()
        val buffer = RadioBuffer(cache, "https://radio.example/live.m3u8", http, retryDelayMs = 10)
        try {
            awaitCondition { buffer.window.latestSequence >= 3 }
            assertEquals(3L, buffer.window.latestSequence)
            assertEquals(3, playlist(buffer.window.read("live.m3u8")).segments.size)
            assertArrayEquals(sample("mp3"), buffer.window.read("0.mp3"))
        } finally { buffer.close() }
    }

    @Test fun wrappersResolveOnceAndHlsMasterSelectsGoodAudioVariant() {
        for (wrapper in listOf("#EXTM3U\nhttps://radio.example/master.m3u8\n", "[playlist]\nFile1=https://radio.example/master.m3u8\n")) {
            val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests.add(chain.request().url.encodedPath)
                val data = when (chain.request().url.encodedPath) {
                    "/listen.m3u" -> wrapper.toByteArray()
                    "/master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=48000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nhigh.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=320000\nhuge.m3u8\n".toByteArray()
                    "/high.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.1,\none.mp3\n#EXTINF:2.1,\ntwo.mp3\n".toByteArray()
                    else -> sample("mp3")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(data.toResponseBody()).build()
            }.build()
            val buffer = RadioBuffer(cache, "https://radio.example/listen.m3u", http)
            try {
                assertEquals(2, playlist(buffer.window.read("live.m3u8")).segments.size)
                assertTrue(requests.contains("/high.m3u8"))
                assertFalse(requests.contains("/low.m3u8"))
                assertFalse(requests.contains("/huge.m3u8"))
            } finally { buffer.close() }
        }
    }

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
