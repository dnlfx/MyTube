package app.mytube.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.media3.common.PlaybackParameters
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.mytube.data.formatPosition
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** Live snapshot of the player, updated by a listener plus a light position poll. */
private class PlayerUiState {
    var isPlaying by mutableStateOf(false)
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    var firstFrame by mutableStateOf(false)
    var aspect by mutableFloatStateOf(16f / 9f)
    var position by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
}

/**
 * Letterboxes the video inside whatever box it's given. Done in layout rather than by reading
 * the box size in composition, so the mini player can resize the video every frame of a drag
 * without recomposing anything.
 */
private fun Modifier.fitAspect(aspect: Float): Modifier = layout { measurable, constraints ->
    val boxW = constraints.maxWidth
    val boxH = if (constraints.hasBoundedHeight) constraints.maxHeight else (boxW / aspect).roundToInt()
    val wide = boxH > 0 && boxW.toFloat() / boxH > aspect
    val w = if (wide) (boxH * aspect).roundToInt() else boxW
    val h = if (wide) boxH else (boxW / aspect).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(0), h.coerceAtLeast(0)))
    layout(boxW, boxH) { placeable.place((boxW - w) / 2, (boxH - h) / 2) }
}

/** Tapping the speed chip steps through these. */
private val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)

private fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

@Composable
fun VideoPlayer(
    player: Player,
    thumbnail: String?,
    quality: String?,
    error: String?,
    fullscreen: Boolean,
    hdr: Boolean,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown small (the picture-in-picture window, or the mini player): video only, no controls or gestures. */
    pip: Boolean = false,
    /** The video can't be played at all (e.g. members only): no spinner and no controls. */
    unavailable: Boolean = false,
) {
    val ui = remember(player) { PlayerUiState() }
    val haptics = LocalHapticFeedback.current

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { ui.isPlaying = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { ui.playbackState = state }
            override fun onRenderedFirstFrame() { ui.firstFrame = true }
            override fun onPlaybackParametersChanged(params: PlaybackParameters) { ui.speed = params.speed }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    ui.aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
        }
        player.addListener(listener)
        ui.isPlaying = player.isPlaying
        ui.playbackState = player.playbackState
        ui.speed = player.playbackParameters.speed
        // A player that is already showing video (e.g. after rotating) needs no thumbnail cover.
        if (player.videoSize.width > 0) {
            ui.firstFrame = player.playbackState != Player.STATE_IDLE
            val size = player.videoSize
            ui.aspect = size.width * size.pixelWidthHeightRatio / size.height
        }
        onDispose { player.removeListener(listener) }
    }

    var controlsVisible by remember { mutableStateOf(true) }

    // The clock and seek bar are only on screen with the controls, so only poll while they are.
    LaunchedEffect(player, controlsVisible) {
        while (controlsVisible) {
            ui.position = player.currentPosition
            ui.buffered = player.bufferedPosition
            ui.duration = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }
    var interactionTick by remember { mutableIntStateOf(0) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    val hold = remember(player) { HoldToSpeed() }

    // Hide controls a few seconds after the last touch while playing.
    LaunchedEffect(controlsVisible, ui.isPlaying, interactionTick) {
        if (controlsVisible && ui.isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(650)
            seekFlash = null
        }
    }

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(player, pip) {
                if (pip) return@pointerInput
                detectTapGestures(
                    onPress = {
                        tryAwaitRelease()
                        hold.stop()
                    },
                    onLongPress = {
                        if (hold.start(player)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controlsVisible = false
                        }
                    },
                    onTap = { controlsVisible = !controlsVisible; interactionTick++ },
                    onDoubleTap = { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (offset.x < size.width / 2) {
                            player.seekBack(); seekFlash = "−10s"
                        } else {
                            player.seekForward(); seekFlash = "+10s"
                        }
                        interactionTick++
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        PlayerSurface(
            player = player,
            // A SurfaceView lives in its own window layer and lags behind when the page is dragged,
            // so it is only used for HDR, which a TextureView can't show.
            surfaceType = if (hdr) SURFACE_TYPE_SURFACE_VIEW else SURFACE_TYPE_TEXTURE_VIEW,
            modifier = Modifier.fitAspect(ui.aspect),
        )

        // Cover the surface with the thumbnail until the first frame arrives.
        AnimatedVisibility(!ui.firstFrame, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            app.mytube.ui.components.SharpImage(thumbnail, Modifier.fillMaxSize())
        }

        val buffering = !unavailable && (
            ui.playbackState == Player.STATE_BUFFERING ||
                (ui.playbackState == Player.STATE_IDLE && error == null)
            )
        if (unavailable) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(
                    androidx.compose.material.icons.Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        if (buffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = if (pip) 2.dp else 2.5.dp,
                modifier = Modifier.size(if (pip) 24.dp else 40.dp),
            )
        }

        AnimatedVisibility(
            visible = !pip && !unavailable && (controlsVisible && !buffering || error != null || ui.playbackState == Player.STATE_ENDED),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(
                ui = ui,
                player = player,
                quality = quality,
                error = error,
                fullscreen = fullscreen,
                onToggleFullscreen = onToggleFullscreen,
                onRetry = onRetry,
                onInteract = { interactionTick++ },
            )
        }

        HoldSpeedBadge(
            hold.holding,
            Modifier.align(Alignment.TopCenter).padding(top = if (fullscreen) 24.dp else 12.dp),
        )

        seekFlash?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    ui: PlayerUiState,
    player: Player,
    quality: String?,
    error: String?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onInteract: () -> Unit,
) {
    // While dragging the seek bar, the clock shows where you'll land.
    var scrub by remember { mutableStateOf<Long?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.3f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.7f),
                ),
            ),
    ) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(if (fullscreen) 20.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (error == null) {
                Text(
                    speedLabel(ui.speed),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = if (ui.speed != 1f) 0.3f else 0.16f))
                        .clickable {
                            val next = SPEEDS[(SPEEDS.indexOf(ui.speed).coerceAtLeast(0) + 1) % SPEEDS.size]
                            player.setPlaybackSpeed(next)
                            onInteract()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (quality != null) {
                Text(
                    quality,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        if (error != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                RoundButton(Icons.Rounded.Replay, "Retry", 56) { onRetry() }
            }
            return@Box
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.Rounded.Replay10, "Back 10 seconds", 48) { player.seekBack(); onInteract() }
            val ended = ui.playbackState == Player.STATE_ENDED
            Crossfade(targetState = if (ended) 2 else if (ui.isPlaying) 1 else 0, label = "playPause") { mode ->
                val icon = when (mode) {
                    2 -> Icons.Rounded.Replay
                    1 -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                }
                RoundButton(icon, if (mode == 1) "Pause" else "Play", 68) {
                    when {
                        ended -> { player.seekTo(0); player.play() }
                        player.isPlaying -> player.pause()
                        else -> player.play()
                    }
                    onInteract()
                }
            }
            RoundButton(Icons.Rounded.Forward10, "Forward 10 seconds", 48) { player.seekForward(); onInteract() }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = if (fullscreen) 24.dp else 12.dp, vertical = if (fullscreen) 16.dp else 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatPosition(scrub ?: ui.position)} / ${formatPosition(ui.duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onToggleFullscreen(); onInteract() }) {
                    Icon(
                        if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                        tint = Color.White,
                    )
                }
            }
            SeekBar(
                position = ui.position,
                buffered = ui.buffered,
                duration = ui.duration,
                onSeek = {
                    player.seekTo(it)
                    ui.position = it
                    scrub = null
                    onInteract()
                },
                onScrub = { scrub = it; onInteract() },
            )
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: Int,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** A slim scrubber that thickens under the finger. */
@Composable
private fun SeekBar(position: Long, buffered: Long, duration: Long, onSeek: (Long) -> Unit, onScrub: (Long?) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val active = dragFraction != null
    val thickness by animateDpAsState(if (active) 6.dp else 3.dp, label = "seekThickness")
    val thumb by animateDpAsState(if (active) 16.dp else 12.dp, label = "seekThumb")
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) onSeek((offset.x / size.width * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        val f = (it.x / size.width).coerceIn(0f, 1f)
                        dragFraction = f
                        onScrub((f * duration).toLong())
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragFraction = f
                        onScrub((f * duration).toLong())
                    },
                    onDragEnd = {
                        dragFraction?.let { if (duration > 0) onSeek((it * duration).toLong()) }
                        dragFraction = null
                        onScrub(null)
                    },
                    onDragCancel = { dragFraction = null; onScrub(null) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)))
        Box(Modifier.width(width * bufferedFraction).height(thickness).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.38f)))
        Box(Modifier.width(width * fraction).height(thickness).clip(RoundedCornerShape(50)).background(accent))
        Box(
            Modifier
                .padding(start = (width * fraction - thumb / 2).coerceAtLeast(0.dp))
                .size(thumb)
                .clip(CircleShape)
                .background(accent),
        )
    }
}
