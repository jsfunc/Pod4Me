package com.pod4me.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/** A surface for the service's existing player; controls remain in the regular Player tab. */
@Composable
internal fun VideoSurface(player: Player?, aspectRatio: Float, playing: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio.coerceIn(.25f, 4f))) {
    if (player == null) return
    key(player) {
        AndroidView(
            factory = { TextureView(it) },
            modifier = modifier.background(Color.Black),
            onReset = null,
            onRelease = { view -> player.clearVideoTextureView(view); view.keepScreenOn = false },
            update = { view -> player.setVideoTextureView(view); view.keepScreenOn = playing },
        )
    }
}
