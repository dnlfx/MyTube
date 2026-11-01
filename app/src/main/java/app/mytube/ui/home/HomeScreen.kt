package app.mytube.ui.home

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
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
import app.mytube.data.Channel
import app.mytube.data.Feed
import app.mytube.data.History
import app.mytube.data.Subscriptions
import app.mytube.data.Suggestions
import app.mytube.data.Video
import app.mytube.data.YouTube
import app.mytube.ui.components.Avatar
import app.mytube.ui.components.SearchPill
import app.mytube.ui.components.Thumbnail
import app.mytube.ui.components.VideoCard
import app.mytube.ui.components.VideoCardSkeleton
import app.mytube.ui.theme.Motion
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HomeFeed(val label: String) { FOR_YOU("For you"), SUBSCRIPTIONS("Subscriptions") }

@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val history by History.entries.collectAsState()
    val positions by History.positionsFlow.collectAsState()
    val subscriptions by Subscriptions.channels.collectAsState()
    val subsFeed by Feed.state.collectAsState()
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(HomeFeed.FOR_YOU) }
    // Bumped by pull-to-refresh on "For you" to bring different picks to the top.
    var seed by rememberSaveable { mutableIntStateOf(0) }
    val followed = remember(subscriptions) { subscriptions.mapTo(HashSet()) { Subscriptions.channelKey(it.url) } }
    val forYou by produceState(
        initialValue = remember { Suggestions.build(history, limit = FEED_MAX) },
        history, followed, seed,
    ) {
        value = withContext(Dispatchers.Default) {
            Suggestions.build(history, limit = FEED_MAX, followed = followed, seed = seed)
        }
    }
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
            .take(12)
            .toList()
    }

    // Uploads refresh on their own when they're stale; the cache keeps this cheap.
    LaunchedEffect(subscriptions) { Feed.refresh(subscriptions) }
    LaunchedEffect(subscriptions.size) {
        if (subscriptions.isEmpty() && mode == HomeFeed.SUBSCRIPTIONS) mode = HomeFeed.FOR_YOU
    }

    val feed = if (mode == HomeFeed.FOR_YOU) forYou else subsFeed.videos

    // Show the feed 10 at a time and reveal more as you near the bottom.
    var shown by rememberSaveable(mode) { mutableIntStateOf(PAGE) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, feed.size, mode) {
        if (nearEnd && shown < feed.size) shown += PAGE
    }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    var refreshingPicks by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = if (mode == HomeFeed.SUBSCRIPTIONS) subsFeed.loading && subsFeed.videos.isNotEmpty() else refreshingPicks,
        onRefresh = {
            scope.launch {
                if (mode == HomeFeed.SUBSCRIPTIONS) {
                    Feed.refresh(subscriptions, force = true)
                    kotlinx.coroutines.delay(300)
                } else {
                    refreshingPicks = true
                    seed++
                    kotlinx.coroutines.delay(450)
                    refreshingPicks = false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = insets) {
            item(key = "top", contentType = "top") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.padding(top = 12.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Wordmark()
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onOpenHistory) {
                            Icon(Icons.Rounded.History, contentDescription = "Watch history")
                        }
                    }
                    Text(
                        greeting(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No ads. No Shorts. Nothing tracked.",
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
                    ContinueWatching(continueWatching, onOpenVideo)
                }
            }

            if (history.isNotEmpty() || subscriptions.isNotEmpty()) {
                item(key = "switch", contentType = "switch") {
                    FeedSwitch(mode, showSubscriptions = subscriptions.isNotEmpty()) { mode = it }
                }
            }

            when {
                mode == HomeFeed.SUBSCRIPTIONS -> {
                    item(key = "subs-row", contentType = "subs-row") { FollowedRow(subscriptions, onOpenChannel) }
                    if (subsFeed.videos.isEmpty()) {
                        if (subsFeed.loading) {
                            items(3, key = { "skeleton$it" }, contentType = { "skeleton" }) { VideoCardSkeleton() }
                        } else {
                            item(key = "subs-empty") {
                                EmptyNote(
                                    "Nothing new yet",
                                    if (subsFeed.failed > 0) "Couldn't reach YouTube. Pull down to try again."
                                    else "New uploads from channels you follow will show up here.",
                                )
                            }
                        }
                    }
                }
                forYou.isEmpty() -> item(key = "empty") {
                    EmptyNote(
                        "Your feed starts here",
                        "Watch a few videos and this page will fill with picks based on them.",
                    )
                }
            }

            items(feed.take(shown), key = { mode.name + it.url }, contentType = { "video" }) { video ->
                VideoCard(
                    video,
                    onClick = { onOpenVideo(video.url) },
                    onChannel = onOpenChannel,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun FeedSwitch(selected: HomeFeed, showSubscriptions: Boolean, onSelect: (HomeFeed) -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeFeed.entries.forEach { feed ->
            if (feed == HomeFeed.SUBSCRIPTIONS && !showSubscriptions) return@forEach
            val active = feed == selected
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                Motion.standard(),
                label = "feedChipBg",
            )
            val fg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                Motion.standard(),
                label = "feedChipFg",
            )
            Text(
                feed.label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(feed) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ContinueWatching(videos: List<Video>, onOpenVideo: (String) -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            "Continue watching",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(videos, key = { "cw:" + it.url }, contentType = { "cw" }) { video ->
                Column(
                    Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenVideo(video.url) },
                ) {
                    Thumbnail(video, Modifier.fillMaxWidth(), corner = 16)
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

/** The channels you follow, as a row of avatars. Tap one to open it. */
@Composable
private fun FollowedRow(channels: List<Channel>, onOpenChannel: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(channels, key = { "follow:" + it.url }) { channel ->
            Column(
                Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenChannel(channel.url) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(channel.avatar, 52)
                Text(
                    channel.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
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
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
    Spacer(Modifier.size(8.dp))
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
private const val FEED_MAX = 200

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
