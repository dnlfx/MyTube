package app.mytube.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import android.view.OrientationEventListener
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.runtime.collectAsState
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
import app.mytube.ui.components.SubscribeButton
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
    ReportToPip(vm.player)
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
            hdr = vm.hdr,
            onToggleFullscreen = toggleFullscreen,
            onRetry = vm::retryPlayback,
            modifier = modifier,
        )
    }

    if (Pip.active) {
        // The floating window: just the video, no controls or page.
        VideoPlayer(
            player = vm.player,
            thumbnail = thumbnail,
            quality = null,
            error = vm.playbackError,
            fullscreen = true,
            hdr = vm.hdr,
            onToggleFullscreen = {},
            onRetry = vm::retryPlayback,
            pip = true,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (landscape) {
        player(Modifier.fillMaxSize())
        return
    }

    // Swipe the video down to close it. The finger moves a plain float that only the draw layer
    // reads, so dragging never recomposes or re-lays out the page. Letting go springs back, or
    // flings the page away when dragged far enough or flicked fast enough.
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissDistance = with(density) { 140.dp.toPx() }
    val flingVelocity = with(density) { 1200.dp.toPx() }
    val cornerPx = with(density) { 28.dp.toPx() }
    val velocity = remember { VelocityTracker() }
    val haptics = LocalHapticFeedback.current
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val release: (Float) -> Unit = { vy ->
        settling = scope.launch {
            if (dragY > dismissDistance || vy > flingVelocity) {
                animate(dragY, dragY + 1600f, initialVelocity = vy.coerceAtLeast(2000f),
                    animationSpec = tween(220, easing = FastOutLinearInEasing)) { v, _ -> dragY = v }
                onBack()
            } else {
                animate(dragY, 0f, initialVelocity = vy,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> dragY = v }
            }
        }
    }
    val smoothSurface = !vm.hdr

    // The drag is read on this outer box, which never moves. Reading it on the moving page would
    // measure the finger against a target that is itself sliding, which is what made it stutter.
    // The list consumes its own scrolls first, so only drags that start on the video get here.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        settling?.cancel()
                        velocity.resetTracking()
                    },
                    onDragEnd = { release(velocity.calculateVelocity().y) },
                    onDragCancel = { release(0f) },
                ) { change, dy ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val before = dragY
                    dragY = (dragY + dy).coerceAtLeast(0f)
                    // A light tick when the drag passes the point where letting go closes the video.
                    if ((before < dismissDistance) != (dragY < dismissDistance)) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
            },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragY
                // A TextureView moves with the page, so it can also shrink and round its corners.
                // The HDR SurfaceView can't, so it only slides.
                if (smoothSurface) {
                    val progress = (dragY / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val scale = 1f - progress * 0.12f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    shape = RoundedCornerShape((cornerPx * (progress * 6f).coerceAtMost(1f)).toDp())
                    clip = dragY > 0f
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .statusBarsPadding()
        ) {
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

    val loadedComments = (vm.comments as? WatchViewModel.CommentsState.Loaded)?.comments.orEmpty()
    val sortedComments = remember(loadedComments, vm.commentSort) { sortComments(loadedComments, vm.commentSort) }

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
        // Tabs don't wait for the video: comments and suggestions load on their own, and only
        // "Up next" (which comes with the video's page) shows a placeholder until it arrives.
        when (tab) {
            WatchTab.UP_NEXT -> if (details == null) {
                item { LoadingRows() }
            } else {
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
                    if (!c.disabled && c.comments.isNotEmpty()) {
                        item(key = "comment-sort", contentType = "sort") {
                            CommentSortRow(vm.commentSort, vm::sortComments)
                        }
                    }
                    items(sortedComments, key = { "comment:" + it.id }, contentType = { "comment" }) { comment ->
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

/** Pinned comments stay on top; the rest follow the chosen order. */
private fun sortComments(list: List<Comment>, sort: WatchViewModel.CommentSort): List<Comment> {
    val (pinned, rest) = list.partition { it.pinned }
    val ordered = when (sort) {
        WatchViewModel.CommentSort.LIKES -> rest.sortedByDescending { it.likes }
        WatchViewModel.CommentSort.NEWEST -> rest.sortedByDescending { it.postedAt }
    }
    return pinned + ordered
}

@Composable
private fun CommentSortRow(
    selected: WatchViewModel.CommentSort,
    onSelect: (WatchViewModel.CommentSort) -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WatchViewModel.CommentSort.entries.forEach { sort ->
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
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(50))
                .then(if (channelUrl != null) Modifier.clickable { onOpenChannel(channelUrl) } else Modifier)
                .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(video?.channelAvatar, 40)
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    video?.channelName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
        if (video != null && channelUrl != null) {
            SubscribeButton(
                remember(channelUrl, video.channelName, video.channelAvatar, details?.subscriberCount) {
                    app.mytube.data.Channel(channelUrl, video.channelName, video.channelAvatar, details?.subscriberCount ?: -1, false)
                },
            )
        }
        }

        if (video != null) Actions(video)
        if (details != null && details.description.isNotBlank()) Description(details.description)
        Spacer(Modifier.height(6.dp))
    }
}

/** Save for later and share, one tap each. */
@Composable
private fun Actions(video: Video) {
    val saved by app.mytube.data.WatchLater.videos.collectAsState()
    val isSaved = app.mytube.data.WatchLater.contains(video.url, saved)
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionChip(
            if (isSaved) androidx.compose.material.icons.Icons.Rounded.Bookmark
            else androidx.compose.material.icons.Icons.Rounded.BookmarkBorder,
            if (isSaved) "Saved" else "Save",
        ) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            app.mytube.data.WatchLater.toggle(video)
        }
        ActionChip(androidx.compose.material.icons.Icons.Rounded.Share, "Share") {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, video.title)
                putExtra(android.content.Intent.EXTRA_TEXT, video.url)
            }
            context.startActivity(android.content.Intent.createChooser(send, null))
        }
    }
}

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
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

/** Tells the activity whether leaving the app should shrink this video into a floating window. */
@Composable
private fun ReportToPip(player: Player) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    Pip.owner = player
                    onVideoSizeChanged(player.videoSize)
                }
                if (Pip.owner === player) Pip.playing = isPlaying
            }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (Pip.owner === player && size.width > 0 && size.height > 0) Pip.aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(listener)
        if (player.isPlaying) {
            Pip.owner = player
            Pip.playing = true
        }
        onDispose {
            player.removeListener(listener)
            // Another video may have taken over already while the screens crossfaded.
            if (Pip.owner === player) {
                Pip.owner = null
                Pip.playing = false
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
