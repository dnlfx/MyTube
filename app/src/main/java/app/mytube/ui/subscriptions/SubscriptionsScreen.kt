package app.mytube.ui.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mytube.data.Channel
import app.mytube.data.Feed
import app.mytube.data.Subscriptions
import app.mytube.ui.components.Avatar
import app.mytube.ui.components.MessageState
import app.mytube.ui.components.TabTopBar
import app.mytube.ui.components.VideoCard
import app.mytube.ui.components.VideoCardSkeleton

/** New uploads from every channel you follow, newest first. */
@Composable
fun SubscriptionsScreen(onSearch: () -> Unit, onOpenVideo: (String) -> Unit, onOpenChannel: (String) -> Unit) {
    val subscriptions by Subscriptions.channels.collectAsState()
    val feed by Feed.state.collectAsState()
    // Uploads refresh on their own when they're stale; the cache keeps this cheap.
    LaunchedEffect(subscriptions) { Feed.refresh(subscriptions) }

    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, feed.videos.size) {
        if (nearEnd && shown < feed.videos.size) shown += PAGE
    }

    Column(Modifier.fillMaxSize()) {
        TabTopBar("Subscriptions", onSearch)
        if (subscriptions.isEmpty()) {
            MessageState(
                "Follow channels you like",
                "Tap Subscribe on any channel or under a video. Their new uploads land here, and who you follow never leaves this phone.",
            )
            return@Column
        }
        PullToRefreshBox(
            isRefreshing = feed.loading && feed.videos.isNotEmpty(),
            onRefresh = { Feed.refresh(subscriptions, force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "channels", contentType = "channels") { FollowedRow(subscriptions, onOpenChannel) }
                if (feed.videos.isEmpty()) {
                    if (feed.loading) {
                        items(3, key = { "skeleton$it" }, contentType = { "skeleton" }) { VideoCardSkeleton() }
                    } else {
                        item(key = "empty") {
                            MessageState(
                                "Nothing new yet",
                                if (feed.failed > 0) "Couldn't reach YouTube. Pull down to try again."
                                else "New uploads from channels you follow will show up here.",
                            )
                        }
                    }
                }
                items(feed.videos.take(shown), key = { it.url }, contentType = { "video" }) { video ->
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
}

/** The channels you follow, as a row of avatars. Tap one to open it. */
@Composable
private fun FollowedRow(channels: List<Channel>, onOpenChannel: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
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

private const val PAGE = 10
