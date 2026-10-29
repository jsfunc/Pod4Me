package com.pod4me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pod4me.R

/** Coil shares memory/disk caching and sizes image decoding to the displayed thumbnail. */
@Composable
fun FeedArtwork(url: String?, size: Dp = 40.dp, modifier: Modifier = Modifier.size(size)) {
    val placeholder = painterResource(R.drawable.ic_feed_placeholder)
    AsyncImage(
        model = url?.takeIf(String::isNotBlank),
        contentDescription = null, // The feed/episode name is already accessible next to it.
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Measures the text/actions first, then fits artwork to their full height in one layout pass.
 * Avoids intrinsic measurement, which is unsupported by subcomposed tooltip content.
 */
@Composable
fun ArtworkRow(url: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Layout(modifier = modifier, content = {
        FeedArtwork(url, modifier = Modifier)
        Column(verticalArrangement = Arrangement.Center, content = content)
    }) { measurables, constraints ->
        val artworkWidth = 72.dp.roundToPx().coerceAtMost(constraints.maxWidth)
        val gap = 8.dp.roundToPx().coerceAtMost(constraints.maxWidth - artworkWidth)
        val contentWidth = (constraints.maxWidth - artworkWidth - gap).coerceAtLeast(0)
        val details = measurables[1].measure(constraints.copy(
            minWidth = contentWidth, maxWidth = contentWidth,
            minHeight = maxOf(constraints.minHeight, artworkWidth).coerceAtMost(constraints.maxHeight),
        ))
        val artwork = measurables[0].measure(Constraints.fixed(artworkWidth, details.height))
        layout(constraints.maxWidth, details.height) {
            artwork.placeRelative(0, 0)
            details.placeRelative(artworkWidth + gap, 0)
        }
    }
}
