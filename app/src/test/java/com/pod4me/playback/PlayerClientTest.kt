package com.pod4me.playback

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.pod4me.PodApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PodApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerClientTest {
    @Test fun countdownAdvancesLocallyWithoutNewSessionExtras() {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val engine = ExoPlayer.Builder(app).build()
        val session = MediaSession.Builder(app, engine).build()
        val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
        session.setSessionExtras(android.os.Bundle().apply { putLong(PlaybackService.SLEEP_DEADLINE, deadline) })
        val client = PlayerClient(app) { MediaController.Builder(app, session.token).buildAsync() }
        try {
            client.connect(); shadowOf(Looper.getMainLooper()).idle(); client.refresh()
            val before = client.state.value.sleepRemaining
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
            client.refresh()
            assertEquals(before - 3000, client.state.value.sleepRemaining)
            assertEquals(deadline, session.sessionExtras.getLong(PlaybackService.SLEEP_DEADLINE))
        } finally { client.disconnect(); session.release(); engine.release() }
    }

    @Test fun rotationDetachesBeforeDisconnectAndReattachesWithoutResettingPlayback() {
        val app = RuntimeEnvironment.getApplication() as PodApplication
        val engine = ExoPlayer.Builder(app).build()
        var output: Surface? = null
        val observed = object : ForwardingPlayer(engine) {
            override fun setVideoSurface(surface: Surface?) { output = surface }
        }
        val session = MediaSession.Builder(app, observed).build()
        val client = PlayerClient(app) { MediaController.Builder(app, session.token).buildAsync() }
        val portrait = TextureView(app).apply { setSurfaceTexture(SurfaceTexture(0)) }
        val landscape = TextureView(app).apply { setSurfaceTexture(SurfaceTexture(0)) }
        try {
            engine.setMediaItem(MediaItem.Builder().setMediaId("video").setUri("https://example.com/video.mp4").build(), 12000)
            engine.setPlaybackSpeed(1.5f)
            engine.playWhenReady = true
            client.connect()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(client.state.value.connected)
            val old = requireNotNull(client.videoPlayer)
            old.setVideoTextureView(portrait)
            assertNotNull(output)

            // Fullscreen swaps views on the same controller; late disposal must not clear the new one.
            old.setVideoTextureView(landscape)
            val fullscreenOutput = output
            assertNotNull(fullscreenOutput)
            old.clearVideoTextureView(portrait)
            assertSame(fullscreenOutput, output)
            assertEquals(12000L, engine.currentPosition)
            old.setVideoTextureView(portrait)
            old.clearVideoTextureView(landscape)
            assertNotNull(output)

            // Activity ON_STOP runs before the old composition/view is disposed on rotation.
            client.disconnect()
            assertNull("Disconnect must detach the service's surface before Android destroys it", output)
            assertNull(portrait.surfaceTextureListener)
            assertEquals("video", engine.currentMediaItem!!.mediaId)
            assertEquals(12000L, engine.currentPosition)
            assertEquals(1.5f, engine.playbackParameters.speed)
            assertTrue(engine.playWhenReady)

            client.connect()
            shadowOf(Looper.getMainLooper()).idle()
            val next = requireNotNull(client.videoPlayer)
            assertNotSame(old, next)
            next.setVideoTextureView(landscape)
            val newOutput = output
            assertNotNull(newOutput)
            old.clearVideoTextureView(portrait) // Late disposal of the portrait composition.
            assertSame(newOutput, output)
            engine.pause()
            client.disconnect()
            assertNull(output)
            assertFalse(engine.playWhenReady)
            assertEquals(12000L, engine.currentPosition)
            client.disconnect() // Repeated lifecycle cleanup is harmless.
        } finally {
            client.disconnect()
            session.release()
            engine.release()
            portrait.surfaceTexture?.release()
            landscape.surfaceTexture?.release()
        }
    }
}
