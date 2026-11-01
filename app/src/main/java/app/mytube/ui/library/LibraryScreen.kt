package app.mytube.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mytube.data.History
import app.mytube.data.Subscriptions
import app.mytube.data.Video
import app.mytube.data.WatchLater
import app.mytube.data.formatAgo
import app.mytube.data.formatCount
import app.mytube.ui.components.Avatar
import app.mytube.ui.components.ChipRow
import app.mytube.ui.components.MessageState
import app.mytube.ui.components.SubscribeButton
import app.mytube.ui.components.TabTopBar
import app.mytube.ui.components.VideoRow

private enum class Shelf(val label: String) { HISTORY("History"), WATCH_LATER("Watch later"), CHANNELS("Channels") }

/** Everything you've kept on this phone: what you watched, what you saved, and who you follow. */
@Composable
fun LibraryScreen(onSearch: () -> Unit, onOpenVideo: (String) -> Unit, onOpenChannel: (String) -> Unit) {
    var shelf by rememberSaveable { mutableStateOf(Shelf.HISTORY) }
    val history by History.entries.collectAsState()
    val saved by WatchLater.videos.collectAsState()
    val channels by Subscriptions.channels.collectAsState()
    var confirm by remember { mutableStateOf<Shelf?>(null) }

    Column(Modifier.fillMaxSize()) {
        TabTopBar("Library", onSearch)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipRow(Shelf.entries, shelf, { it.label }, { shelf = it }, Modifier.weight(1f))
        }

        LazyColumn(Modifier.fillMaxSize()) {
            when (shelf) {
                Shelf.HISTORY -> {
                    if (history.isEmpty()) {
                        item { MessageState("Nothing here yet", "Videos you watch show up here. History never leaves this phone.") }
                    } else {
                        item(key = "history-head") {
                            ShelfHeader("${history.size} watched") { confirm = Shelf.HISTORY }
                        }
                    }
                    items(history, key = { "h:" + it.video.url }, contentType = { "row" }) { entry ->
                        RemovableRow(entry.video, formatAgo(entry.watchedAt), onOpenVideo) { History.remove(entry.video.url) }
                    }
                }
                Shelf.WATCH_LATER -> {
                    if (saved.isEmpty()) {
                        item { MessageState("Nothing saved", "Tap Save under any video to keep it here for later.") }
                    } else {
                        item(key = "saved-head") {
                            ShelfHeader("${saved.size} saved") { confirm = Shelf.WATCH_LATER }
                        }
                    }
                    items(saved, key = { "w:" + it.url }, contentType = { "row" }) { video ->
                        RemovableRow(video, null, onOpenVideo) { WatchLater.remove(video.url) }
                    }
                }
                Shelf.CHANNELS -> {
                    if (channels.isEmpty()) {
                        item { MessageState("No channels yet", "Channels you subscribe to are listed here.") }
                    }
                    items(channels, key = { "c:" + it.url }, contentType = { "channel" }) { channel ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .clickable { onOpenChannel(channel.url) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Avatar(channel.avatar, 48)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    channel.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (channel.subscriberCount > 0) {
                                    Text(
                                        "${formatCount(channel.subscriberCount)} subscribers",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            SubscribeButton(channel)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (which == Shelf.HISTORY) "Clear watch history?" else "Clear Watch later?") },
            text = {
                Text(
                    if (which == Shelf.HISTORY) "This also resets your home feed, Shorts picks and Continue watching."
                    else "Every saved video will be removed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (which == Shelf.HISTORY) {
                        History.clear()
                        app.mytube.data.ShortsHistory.clear()
                    } else {
                        WatchLater.clear()
                    }
                    confirm = null
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShelfHeader(count: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            count,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text("Clear all") }
    }
}

@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.RemovableRow(
    video: Video,
    note: String?,
    onOpenVideo: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
        Box(Modifier.weight(1f)) {
            VideoRow(video, onClick = { onOpenVideo(video.url) }, note = note)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
