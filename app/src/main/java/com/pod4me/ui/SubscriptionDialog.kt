package com.pod4me.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pod4me.R

@Composable
internal fun SubscriptionDialog(model: AppModel, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val search by model.subscriptionSearch.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val url = input.trim().contains("://")
    fun submit() {
        if (busy || input.isBlank()) return
        if (url) { model.subscribe(input.trim()); onDismiss() }
        else model.searchSubscriptions(input.trim())
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add subscription") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it.take(4096) }, label = { Text("Podcast name or URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = if (url) ImeAction.Go else ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }, onGo = { submit() }))
            if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            search.message?.let { Text(it) }
            LazyColumn(Modifier.heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(search.items, key = { it.feedUrl }) { item ->
                    val subscribed = library.subscriptions.firstOrNull { it.url == item.feedUrl }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedArtwork(item.artworkUrl, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val video = subscribed?.id in library.videoFeeds
                                Icon(painterResource(if (video) R.drawable.ic_video else R.drawable.ic_feeds),
                                    contentDescription = if (video) "Video feed" else "Podcast feed", modifier = Modifier.size(14.dp))
                                Text(item.publisher, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (subscribed != null) Icon(painterResource(R.drawable.ic_check), "Subscribed", modifier = Modifier.size(24.dp))
                        else IconButton(enabled = !busy, onClick = { model.subscribeResult(item) }) {
                            Icon(painterResource(R.drawable.ic_add), "Subscribe to ${item.title}")
                        }
                    }
                }
            }
            TextButton(enabled = input.isNotBlank(), onClick = { model.action {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(input.trim())}")))
            } }) { Text("Search on YouTube ↗") }
        }
    }, confirmButton = { TextButton(enabled = !busy && input.isNotBlank(), onClick = ::submit) { Text(if (url) "Subscribe" else "Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
