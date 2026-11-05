package app.mytube.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mytube.data.ChannelPage
import app.mytube.data.Video
import app.mytube.data.YouTube
import app.mytube.data.formatCount
import app.mytube.ui.components.Avatar
import app.mytube.ui.components.MessageState
import app.mytube.ui.components.SubscribeButton
import app.mytube.ui.components.VideoCard
import app.mytube.ui.components.VideoCardSkeleton
import app.mytube.ui.components.avatarKey
import app.mytube.ui.components.sharedMorph
import app.mytube.ui.search.friendlyMessage
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

class ChannelViewModel(saved: SavedStateHandle) : ViewModel() {
    val url: String = saved.get<String>("url").orEmpty()

    var page by mutableStateOf<ChannelPage?>(null)
        private set
    var videos by mutableStateOf<List<Video>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set

    enum class Sort(val label: String) { LATEST("Latest"), VIEWS("Most viewed") }

    var sort by mutableStateOf(Sort.LATEST)
        private set

    /** Videos in the chosen order. YouTube already returns them newest first. */
    val sorted: List<Video>
        get() = when (sort) {
            Sort.LATEST -> videos
            Sort.VIEWS -> videos.sortedByDescending { it.viewCount }
        }

    fun sortBy(value: Sort) {
        sort = value
        // YouTube has no "most viewed" order we can ask for, so sort what's loaded and pull in a
        // few more pages first so older hits are likely included.
        if (value == Sort.VIEWS) viewModelScope.launch {
            repeat(4) {
                val before = videos.size
                while (loadingMore) kotlinx.coroutines.delay(100)
                if (page?.hasMore != true) return@launch
                loadMore()
                while (loadingMore) kotlinx.coroutines.delay(100)
                if (videos.size == before) return@launch
            }
        }
    }

    init { load() }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { YouTube.channel(url) }
                .onSuccess { page = it; videos = it.videos }
                .onFailure { error = it.friendlyMessage() }
        }
    }

    fun loadMore() {
        val p = page ?: return
        if (loadingMore || !p.hasMore) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { YouTube.moreChannelVideos(p) }.onSuccess { next ->
                page = next
                val seen = videos.mapTo(HashSet()) { it.url }
                videos = videos + next.videos.filter { it.url !in seen }
            }
            loadingMore = false
        }
    }
}

@Composable
fun ChannelScreen(
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit,
    vm: ChannelViewModel = viewModel(),
) {
    val page = vm.page
    val state = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, vm.videos.size) { if (nearEnd) vm.loadMore() }
    val sorted = remember(vm.videos, vm.sort) { vm.sorted }
    // Keep a followed channel's name and picture up to date.
    LaunchedEffect(page?.channel) { page?.channel?.let(app.mytube.data.Subscriptions::refresh) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    app.mytube.ui.watch.LocalDockPadding.current,
            ),
        ) {
            item(key = "header") { Header(vm.url, page) }
            when {
                vm.error != null -> item {
                    MessageState("Couldn't load this channel", vm.error.orEmpty()) {
                        FilledTonalButton(onClick = vm::load) { Text("Try again") }
                    }
                }
                page == null -> items(3) { VideoCardSkeleton() }
                vm.videos.isEmpty() -> item { MessageState("No videos", "This channel hasn't posted any regular videos.") }
                else -> {
                    item(key = "sort", contentType = "sort") { SortRow(vm.sort, vm::sortBy) }
                    items(sorted, key = { it.url }, contentType = { "video" }) { video ->
                        VideoCard(video, onClick = { onOpenVideo(video.url) })
                    }
                    if (vm.loadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        FilledTonalIconButton(
            onClick = onBack,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.4f),
                contentColor = Color.White,
            ),
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
    }
}

@Composable
private fun Header(url: String, page: ChannelPage?) {
    val background = MaterialTheme.colorScheme.background
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.6f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = page?.banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to background)),
            )
        }
        Row(
            Modifier.padding(horizontal = 20.dp).offset(y = (-28).dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(background).padding(3.dp)) {
                Avatar(page?.channel?.avatar, 78, Modifier.sharedMorph(avatarKey(url)))
            }
            Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                Text(
                    page?.channel?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                page?.channel?.subscriberCount?.takeIf { it > 0 }?.let {
                    Text(
                        "${formatCount(it)} subscribers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (page != null) SubscribeButton(page.channel, Modifier.padding(bottom = 8.dp))
        }
        if (!page?.description.isNullOrBlank()) {
            Text(
                page!!.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).offset(y = (-16).dp),
            )
        }
    }
}

@Composable
private fun SortRow(selected: ChannelViewModel.Sort, onSelect: (ChannelViewModel.Sort) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelViewModel.Sort.entries.forEach { sort ->
            val active = sort == selected
            Text(
                sort.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable { onSelect(sort) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
