package app.mytube.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import android.view.OrientationEventListener
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import app.mytube.data.Comment
import app.mytube.ui.theme.Motion
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import app.mytube.data.Video
import app.mytube.data.VideoDetails
import app.mytube.data.formatCount
import app.mytube.data.videoMeta
import app.mytube.ui.components.Avatar
import app.mytube.ui.components.MessageState
import app.mytube.ui.components.VideoRow
import app.mytube.ui.components.shimmerBrush
import app.mytube.ui.components.sharedMorph
import app.mytube.ui.components.thumbKey

@Composable
fun WatchScreen(
    url: String,
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    vm: WatchViewModel = viewModel(),
) {
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Full screen whenever the phone is sideways: hide the system bars and give the video everything.
    DisposableEffect(landscape) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (landscape) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    KeepScreenOnWhilePlaying(vm.player)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.savePosition()
        vm.player.pause()
    }

    // Kept here (not in the portrait list) so the tab and scroll position survive a trip to landscape.
    val listState = rememberLazyListState()

    // The full-screen button forces an orientation. Once the phone is physically turned to match,
    // hand control back to the sensor so auto-rotate keeps working in both directions.
    val forced = remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                val want = forced.value ?: return
                if (degrees == ORIENTATION_UNKNOWN) return
                val sideways = degrees in 60..120 || degrees in 240..300
                val upright = degrees in 0..30 || degrees in 330..359
                val matches = if (want == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) sideways else upright
                if (matches) {
                    forced.value = null
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { listener.disable() }
    }
    val force = { orientation: Int ->
        forced.value = orientation
        activity?.requestedOrientation = orientation
    }
    val toggleFullscreen = {
        force(
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        )
    }
    BackHandler(enabled = landscape) { force(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) }

    val ready = vm.state as? WatchViewModel.State.Ready
    val video: Video? = ready?.details?.video ?: vm.preview
    val thumbnail = vm.preview?.thumbnail ?: video?.thumbnail

    val player = @Composable { modifier: Modifier ->
        VideoPlayer(
            player = vm.player,
            thumbnail = thumbnail,
            quality = vm.quality,
            error = vm.playbackError,
            fullscreen = landscape,
            onToggleFullscreen = toggleFullscreen,
            onRetry = vm::retryPlayback,
            modifier = modifier,
        )
    }

    if (landscape) {
        player(Modifier.fillMaxSize())
        return
    }

    // Swipe the video down to close it. The finger moves a plain float that only the draw layer
    // reads, so dragging never recomposes the page; an animation runs only when you let go.
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val dismissDistance = with(LocalDensity.current) { 120.dp.toPx() }
    val settle: (Float, () -> Unit) -> Unit = { target, then ->
        scope.launch {
            animate(dragY, target, animationSpec = Motion.expressive()) { value, _ -> dragY = value }
            then()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = (dragY / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                translationY = dragY
                val scale = 1f - progress * 0.1f
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .statusBarsPadding()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragY > dismissDistance) settle(dragY + dismissDistance) { onBack() }
                            else settle(0f) {}
                        },
                        onDragCancel = { settle(0f) {} },
                    ) { change, dy ->
                        change.consume()
                        dragY = (dragY + dy).coerceAtLeast(0f)
                    }
                }        ) {
            player(Modifier.fillMaxWidth().aspectRatio(16f / 9f).sharedMorph(thumbKey(url)))
        }

        when (val state = vm.state) {
            is WatchViewModel.State.Failed -> MessageState("Couldn't load this video", state.message) {
                FilledTonalButton(onClick = vm::load) { Text("Try again") }
            }
            else -> WatchBody(vm, listState, video, ready?.details, onOpenVideo, onOpenChannel)
        }
    }
}

enum class WatchTab(val label: String) { COMMENTS("Comments"), UP_NEXT("Up next"), SUGGESTED("Suggested") }

@Composable
private fun WatchBody(
    vm: WatchViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    video: Video?,
    details: VideoDetails?,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    var tab by vm::tab

    LaunchedEffect(tab) { if (tab == WatchTab.COMMENTS) vm.loadComments() }

    // Load more comments as the list nears its end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, tab, vm.comments) {
        if (nearEnd && tab == WatchTab.COMMENTS) vm.loadMoreComments()
    }

    // The player sits above this list, so everything here scrolls beneath a fixed video.
    LazyColumn(
        state = listState,
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") { Header(video, details, onOpenChannel) }
        stickyHeader(key = "tabs") {
            Tabs(tab) { selected ->
                tab = selected
            }
        }
        if (details == null) {
            item { LoadingRows() }
            return@LazyColumn
        }
        when (tab) {
            WatchTab.UP_NEXT -> {
                if (details.related.isEmpty()) item { MessageState("Nothing up next", "YouTube didn't suggest anything for this one.") }
                items(details.related, key = { "next:" + it.url }, contentType = { "row" }) { item ->
                    VideoRow(item, onClick = { onOpenVideo(item.url) })
                }
            }
            WatchTab.SUGGESTED -> {
                val picks = vm.suggested
                if (picks.isEmpty()) {
                    item { MessageState("Suggestions grow as you watch", "Watch a few more videos and picks based on your history will show up here.") }
                }
                items(picks, key = { "pick:" + it.url }, contentType = { "row" }) { item ->
                    VideoRow(item, onClick = { onOpenVideo(item.url) })
                }
            }
            WatchTab.COMMENTS -> when (val c = vm.comments) {
                WatchViewModel.CommentsState.Idle, WatchViewModel.CommentsState.Loading -> item { CommentSkeletons() }
                is WatchViewModel.CommentsState.Failed -> item {
                    MessageState("Couldn't load comments", c.message) {
                        FilledTonalButton(onClick = vm::loadComments) { Text("Try again") }
                    }
                }
                is WatchViewModel.CommentsState.Loaded -> {
                    if (c.disabled) item { MessageState("Comments are off", "The creator turned off comments for this video.") }
                    else if (c.comments.isEmpty()) item { MessageState("No comments yet", "Nobody has commented on this video.") }
                    items(c.comments, key = { "comment:" + it.id }, contentType = { "comment" }) { comment ->
                        CommentItem(comment, onOpenChannel)
                    }
                    if (c.loadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(selected: WatchTab, onSelect: (WatchTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        WatchTab.entries.forEach { tab ->
            val active = tab == selected
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                Motion.standard(),
                label = "tabBg",
            )
            val fg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                Motion.standard(),
                label = "tabFg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(tab.label, style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

@Composable
private fun CommentItem(comment: Comment, onOpenChannel: (String) -> Unit) {
    val authorUrl = comment.authorUrl
    val openAuthor = Modifier.then(if (authorUrl != null) Modifier.clickable { onOpenChannel(authorUrl) } else Modifier)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(comment.avatar, 34, Modifier.clip(CircleShape).then(openAuthor))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    comment.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (comment.byCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = openAuthor,
                )
                comment.date?.let {
                    Text(
                        "  ·  $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (comment.pinned) {
                    Text(
                        "  ·  Pinned",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            var expanded by rememberSaveable(comment.id) { mutableStateOf(false) }
            val text = remember(comment.text) {
                androidx.core.text.HtmlCompat.fromHtml(comment.text, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp).animateContentSize().clickable { expanded = !expanded },
            )
            val meta = listOfNotNull(
                comment.likes.takeIf { it > 0 }?.let { "${formatCount(it.toLong())} likes" },
                comment.replies.takeIf { it > 0 }?.let { if (it == 1) "1 reply" else "$it replies" },
                if (comment.hearted) "♥ by creator" else null,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CommentSkeletons() {
    val brush = shimmerBrush()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(brush))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.35f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(0.9f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(0.6f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
private fun Header(video: Video?, details: VideoDetails?, onOpenChannel: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text(
            video?.title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        if (video != null) {
            Text(
                videoMeta(video.viewCount, video.uploaded, video.isLive) +
                    (details?.likeCount?.takeIf { it > 0 }?.let { "  ·  ${formatCount(it)} likes" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val channelUrl = video?.channelUrl
        Row(
            Modifier
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(50))
                .then(if (channelUrl != null) Modifier.clickable { onOpenChannel(channelUrl) } else Modifier)
                .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(video?.channelAvatar, 40)
            Column {
                Text(
                    video?.channelName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                details?.subscriberCount?.takeIf { it > 0 }?.let {
                    Text(
                        "${formatCount(it)} subscribers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (details != null && details.description.isNotBlank()) Description(details.description)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Description(html: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val text = remember(html) {
        androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(14.dp),
    )
}

@Composable
private fun LoadingRows() {
    val brush = shimmerBrush()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(brush))
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(0.45f).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(brush))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOnWhilePlaying(player: Player) {
    val view = LocalView.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { view.keepScreenOn = isPlaying }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            view.keepScreenOn = false
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
