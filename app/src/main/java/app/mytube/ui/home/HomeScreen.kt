package app.mytube.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.mytube.data.History
import app.mytube.data.Subscriptions
import app.mytube.data.Suggestions
import app.mytube.data.Video
import app.mytube.data.YouTube
import app.mytube.ui.components.LogoMark
import app.mytube.ui.components.SearchPill
import app.mytube.ui.components.Thumbnail
import app.mytube.ui.components.VideoCard
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val history by History.entries.collectAsState()
    val positions by History.positionsFlow.collectAsState()
    val subscriptions by Subscriptions.channels.collectAsState()
    val scope = rememberCoroutineScope()
    // Bumped by pull-to-refresh on "For you" to bring different picks to the top.
    var seed by rememberSaveable { mutableIntStateOf(0) }
    val followed = remember(subscriptions) { subscriptions.mapTo(HashSet()) { Subscriptions.channelKey(it.url) } }
    val feedback by app.mytube.data.Feedback.state.collectAsState()
    val built by produceState(
        initialValue = remember { Suggestions.build(history, limit = FEED_MAX) },
        history, followed, seed, feedback,
    ) {
        value = withContext(Dispatchers.Default) {
            Suggestions.build(history, limit = FEED_MAX, followed = followed, seed = seed, feedback = feedback)
        }
    }
    // Hide feedback right away, before the feed is rebuilt.
    val forYou = remember(built, feedback) { built.filterNot { feedback.hides(it) } }
    LaunchedEffect(forYou) { YouTube.remember(forYou) }

    // Videos started but not finished, newest first.
    val continueWatching = remember(history, positions) {
        history.asSequence()
            .filter { entry ->
                val pos = positions[entry.video.url] ?: 0L
                val duration = entry.video.durationSeconds * 1000
                pos > 10_000 && duration > 0 && pos < duration - 15_000
            }
            .map { it.video }
            .take(CONTINUE_MAX)
            .toList()
    }
    var confirmClearContinue by remember { mutableStateOf(false) }

    val feed = forYou

    // Show the feed 10 at a time and reveal more as you near the bottom.
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, feed.size) {
        if (nearEnd && shown < feed.size) shown += PAGE
    }
    val insets = WindowInsets.statusBars.asPaddingValues()
    var refreshingPicks by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshingPicks,
        onRefresh = {
            scope.launch {
                refreshingPicks = true
                seed++
                kotlinx.coroutines.delay(450)
                refreshingPicks = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = insets) {
            item(key = "top", contentType = "top") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.padding(top = 24.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Wordmark()
                    }
                    Text(
                        greeting(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No ads. No account. Nothing tracked.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    SearchPill(onClick = onSearch)
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (continueWatching.isNotEmpty()) {
                item(key = "continue", contentType = "continue") {
                    ContinueWatching(
                        continueWatching,
                        onOpenVideo,
                        onRemove = { History.clearPosition(it) },
                        onClearAll = { confirmClearContinue = true },
                    )
                }
            }

            if (forYou.isEmpty()) {
                item(key = "empty") {
                    EmptyNote(
                        "Your feed starts here",
                        "Watch a few videos and this page will fill with picks based on them.",
                    )
                }
            } else {
                item(key = "label", contentType = "label") {
                    Text(
                        "For you",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                    )
                }
            }

            items(feed.take(shown), key = { it.url }, contentType = { "video" }) { video ->
                VideoCard(
                    video,
                    onClick = { onOpenVideo(video.url) },
                    onChannel = onOpenChannel,
                    modifier = Modifier.animateItem(),
                    feedback = true,
                )
            }
        }
    }

    if (confirmClearContinue) {
        AlertDialog(
            onDismissRequest = { confirmClearContinue = false },
            title = { Text("Clear Continue watching?") },
            text = { Text("Videos stay in your history. They'll just start from the beginning next time.") },
            confirmButton = {
                TextButton(onClick = { History.clearPositions(); confirmClearContinue = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearContinue = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ContinueWatching(
    videos: List<Video>,
    onOpenVideo: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Continue watching",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) { Text("Clear") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(videos, key = { "cw:" + it.url }, contentType = { "cw" }) { video ->
                Column(
                    Modifier
                        .animateItem()
                        .width(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenVideo(video.url) },
                ) {
                    Box {
                        Thumbnail(video, Modifier.fillMaxWidth(), corner = 16)
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                .clickable { onRemove(video.url) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remove from Continue watching",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Text(
                        video.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                    )
                    Text(
                        video.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Wordmark() {
    LogoMark(26.dp)
    Spacer(Modifier.size(10.dp))
    Text(
        buildAnnotatedString {
            append("my")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("tube") }
        },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

private const val PAGE = 10
private const val CONTINUE_MAX = 5
private const val FEED_MAX = 200

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
