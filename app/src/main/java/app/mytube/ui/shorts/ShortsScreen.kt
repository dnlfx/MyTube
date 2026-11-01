package app.mytube.ui.shorts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.mytube.data.Channel
import app.mytube.data.Video
import app.mytube.data.formatCount
import app.mytube.ui.components.Avatar
import app.mytube.ui.components.SubscribeButton
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** A full-screen, swipe-up feed of Shorts. Kept in its own tab so they never mix into the rest of the app. */
@Composable
fun ShortsScreen(onOpenChannel: (String) -> Unit, vm: ShortsViewModel = viewModel()) {
    // Play only while this tab is on screen and the app is in front.
    DisposableEffect(vm) {
        vm.visible = true
        onDispose { vm.visible = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.visible = false }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.visible = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val state = vm.state) {
            ShortsViewModel.State.Loading -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.5.dp,
                modifier = Modifier.align(Alignment.Center).size(36.dp),
            )
            ShortsViewModel.State.Empty -> Message(
                "Shorts show up here",
                "Picks are based on Shorts you like and watch, and on channels you follow. Watch a few videos or follow a channel to get started.",
                onRetry = { vm.load(force = true) },
            )
            is ShortsViewModel.State.Failed -> Message("Couldn't load Shorts", state.message, onRetry = { vm.load(force = true) })
            is ShortsViewModel.State.Ready -> Feed(vm, state.shorts, onOpenChannel)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Feed(vm: ShortsViewModel, shorts: List<Video>, onOpenChannel: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = vm.current) { shorts.size }
    LaunchedEffect(pager, shorts) {
        snapshotFlow { pager.settledPage }.collect { page -> if (page != vm.current || vm.playingUrl == null) vm.onPageShown(page) }
    }

    VerticalPager(
        state = pager,
        beyondViewportPageCount = 1,
        key = { shorts[it].url },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val short = shorts[page]
        ShortPage(
            short = short,
            playing = vm.playingUrl == short.url,
            vm = vm,
            onOpenChannel = onOpenChannel,
        )
    }

    IconButton(
        onClick = { vm.load(force = true) },
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
    ) {
        Icon(Icons.Rounded.Refresh, contentDescription = "New Shorts", tint = Color.White)
    }
}

@Composable
private fun ShortPage(short: Video, playing: Boolean, vm: ShortsViewModel, onOpenChannel: (String) -> Unit) {
    var aspect by remember { mutableFloatStateOf(9f / 16f) }
    var firstFrame by remember(playing) { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val views by app.mytube.data.ShortsHistory.views.collectAsState()
    val liked = app.mytube.data.ShortsHistory.isLiked(short.url, views)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // A heart that pops up in the middle when you double-tap to like.
    var burst by remember { mutableStateOf(0) }
    val like = {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        vm.toggleLike(short)
    }

    if (playing) {
        DisposableEffect(vm.player) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true }
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    paused = !isPlaying && vm.player.playbackState == Player.STATE_READY
                }
            }
            vm.player.addListener(listener)
            onDispose { vm.player.removeListener(listener) }
        }
        LaunchedEffect(Unit) {
            while (true) {
                val d = vm.player.duration
                progress = if (d > 0) (vm.player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
                delay(100)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(playing) {
                detectTapGestures(
                    onTap = { if (playing) vm.togglePlay() else if (vm.error != null) vm.retry() },
                    onDoubleTap = {
                        if (!app.mytube.data.ShortsHistory.isLiked(short.url)) like()
                        burst++
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            PlayerSurface(
                player = vm.player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
            )
        }
        // The thumbnail stays until the video's first frame is drawn.
        AnimatedVisibility(!playing || !firstFrame, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = short.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!playing && vm.error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        if (vm.error != null && vm.playingUrl == null) {
            Text(
                vm.error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(32.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
            )
        }
        AnimatedVisibility(playing && paused, enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut() + scaleOut()) {
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }

        Info(short, onOpenChannel, Modifier.align(Alignment.BottomStart))

        LikeButton(liked, onClick = like, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 150.dp))

        if (burst > 0) {
            val scale = remember(burst) { androidx.compose.animation.core.Animatable(0.4f) }
            val alpha = remember(burst) { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(burst) {
                scale.animateTo(1.15f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 400f))
                alpha.animateTo(0f, androidx.compose.animation.core.tween(350))
            }
            Icon(
                androidx.compose.material.icons.Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
            )
        }

        if (playing) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Color.White))
            }
        }
    }
}

@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (liked) 1f else 0.92f,
        androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "likeScale",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (liked) androidx.compose.material.icons.Icons.Rounded.Favorite
                else androidx.compose.material.icons.Icons.Rounded.FavoriteBorder,
                contentDescription = if (liked) "Unlike" else "Like",
                tint = if (liked) Color(0xFFFF4D7E) else Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            if (liked) "Liked" else "Like",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Info(short: Video, onOpenChannel: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(start = 16.dp, end = 76.dp, top = 48.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val channelUrl = short.channelUrl
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(CircleShape)
                    .then(if (channelUrl != null) Modifier.clickable { onOpenChannel(channelUrl) } else Modifier)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(short.channelAvatar, 34)
                Text(
                    short.channelName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (channelUrl != null) {
                SubscribeButton(
                    remember(channelUrl) { Channel(channelUrl, short.channelName, short.channelAvatar, -1, false) },
                    dark = true,
                )
            }
        }
        Text(
            short.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (short.viewCount >= 0) {
            Text(
                "${formatCount(short.viewCount)} views",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun Message(title: String, body: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onRetry) { Text("Load Shorts") }
    }
}
