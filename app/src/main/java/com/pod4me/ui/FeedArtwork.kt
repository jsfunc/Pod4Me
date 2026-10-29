package com.pod4me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pod4me.R

/** Coil shares memory/disk caching and sizes image decoding to the displayed thumbnail. */
@Composable
fun FeedArtwork(url: String?, size: Dp = 40.dp) {
    val placeholder = painterResource(R.drawable.ic_feed_placeholder)
    AsyncImage(
        model = url?.takeIf(String::isNotBlank),
        contentDescription = null, // The feed/episode name is already accessible next to it.
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
