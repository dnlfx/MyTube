package app.mytube.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
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
fun WatchScreen(url: String, onBack: () -> Unit, onOpenVideo: (String) -> Unit, vm: WatchViewModel = viewModel()) {
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
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.player.pause() }

    val toggleFullscreen = {
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    BackHandler(enabled = landscape) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT }

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

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()) {
            player(Modifier.fillMaxWidth().aspectRatio(16f / 9f).sharedMorph(thumbKey(url)))
        }

        when (val state = vm.state) {
            is WatchViewModel.State.Failed -> MessageState("Couldn't load this video", state.message) {
                FilledTonalButton(onClick = vm::load) { Text("Try again") }
            }
            else -> LazyColumn(
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { Header(video, ready?.details) }
                if (ready == null) {
                    item { LoadingRows() }
                } else {
                    if (ready.details.related.isNotEmpty()) {
                        item {
                            Text(
                                "Up next",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(ready.details.related, key = { it.url }) { item ->
                        VideoRow(item, onClick = { onOpenVideo(item.url) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(video: Video?, details: VideoDetails?) {
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

        Row(
            Modifier.padding(vertical = 16.dp),
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
