package app.mytube.ui.watch

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

// Mini player geometry, in dp.
private const val CARD_HEIGHT = 64f
private const val CARD_MARGIN = 8f
private const val CARD_MAX_WIDTH = 520f
/** Gap between the card's edge and the small video inside it. */
private const val VIDEO_INSET = 6f
private const val MINI_VIDEO_HEIGHT = CARD_HEIGHT - 2 * VIDEO_INSET
private const val MINI_VIDEO_WIDTH = MINI_VIDEO_HEIGHT * 16f / 9f
private const val MINI_CORNER = 8f

/** How far the back gesture shrinks the page before you let go (as a share of the way to the dock). */
private const val BACK_TRAVEL = 0.3f

/**
 * Where the watch sheet is, and the motion between its places.
 *
 * Everything the finger moves is a plain float that only layout and drawing read, so a drag
 * never recomposes the page: each frame just moves and resizes a few boxes.
 */
@Stable
class WatchSheetState internal constructor(private val scope: CoroutineScope) {
    /** 0 is the full page, 1 is docked as the mini player, above 1 is being swiped down and away. */
    var position by mutableFloatStateOf(1f)
        private set
    /** Sideways offset of the mini player while it's swiped away, in pixels. */
    var offsetX by mutableFloatStateOf(0f)
        private set
    /** Runs from 0 to 1 as a newly opened video slides up into view. */
    var entrance by mutableFloatStateOf(1f)
        private set

    /** [position] limited to the range where the video changes size. */
    val progress: Float get() = position.coerceIn(0f, 1f)

    /** True when the open video hides everything behind it, so the app underneath can skip drawing. */
    val coversScreen: Boolean get() = Pip.active || (entrance >= 1f && position <= 0f)

    // Measured by the layout on every pass. Plain fields: only gestures and animations read them.
    /** Pixels the video's top edge travels between the full page and the dock. */
    internal var travel = 1f
    internal var width = 1f
    /** Pixels below the dock past which letting go swipes the mini player away. */
    internal var swipeAway = 1f
    /** Pixels below the dock at which the mini player is fully out of view. */
    internal var offscreen = 1f
    /** A flick faster than this (pixels per second) decides the direction on its own. */
    internal var fling = 1f
    internal var haptics: HapticFeedback? = null
    internal var onDismissed: () -> Unit = {}

    private var job: Job? = null

    /** A newly opened video: the full page slides up from just below and fades in. */
    fun enter() {
        stop()
        position = 0f
        offsetX = 0f
        entrance = 0f
        job = scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = 0.001f)) { v, _ ->
                entrance = v
            }
        }
    }

    fun expand(velocity: Float = 0f) = settle(0f, velocity)

    fun collapse(velocity: Float = 0f) = settle(1f, velocity)

    /** Closes the video, letting the mini player drop out of view first. */
    fun dismiss() {
        if (position < 0.5f) {
            // From the full page, shrink into the dock and keep going down.
            stop()
            job = scope.launch {
                animate(position, 1f + offscreen / travel, animationSpec = spring(dampingRatio = 1f, stiffness = 300f)) { v, _ ->
                    position = v
                }
                finish()
            }
        } else {
            drop(0f)
        }
    }

    /** Stops any motion in progress so the finger can take over. */
    internal fun grab() = stop()

    /** Finishes a motion that a touch interrupted without turning into a drag. */
    internal fun resume() {
        if (job?.isActive == true) return
        when {
            offsetX != 0f -> springBackSideways(0f)
            position > 1f -> collapse()
            position != 0f && position != 1f -> if (position > 0.5f) collapse() else expand()
        }
    }

    internal fun dragBy(dy: Float) {
        val before = position
        position = (position + dy / travel).coerceAtLeast(0f)
        val line = 1f + swipeAway / travel
        if ((before < line) != (position < line)) tick()
    }

    internal fun dragSideways(dx: Float) {
        val before = offsetX
        offsetX += dx
        val line = width * 0.3f
        if ((abs(before) < line) != (abs(offsetX) < line)) tick()
    }

    internal fun backProgress(fraction: Float) {
        stop()
        position = BACK_TRAVEL * fraction
    }

    /** Where the sheet goes when the finger lets go, from where it is and how fast it was moving. */
    internal fun release(vertical: Boolean, velocity: Velocity, startedDocked: Boolean) {
        if (!vertical) {
            val away = abs(offsetX) > width * 0.3f ||
                (abs(velocity.x) > fling && sign(velocity.x) == sign(offsetX))
            if (away) throwSideways(velocity.x) else springBackSideways(velocity.x)
            return
        }
        val v = velocity.y / travel
        when {
            position > 1f -> if ((position - 1f) * travel > swipeAway || velocity.y > fling) drop(v) else collapse(v)
            velocity.y > fling -> collapse(v)
            velocity.y < -fling -> expand(v)
            // A short pull is enough to leave where you started: a fifth of the way.
            startedDocked -> if (position < 0.8f) expand(v) else collapse(v)
            else -> if (position > 0.2f) collapse(v) else expand(v)
        }
    }

    private fun settle(target: Float, velocity: Float) {
        stop()
        job = scope.launch {
            animate(
                initialValue = position,
                targetValue = target,
                initialVelocity = velocity,
                // Slightly under-damped: a flick into the dock dips a touch and settles, like a real object.
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 400f, visibilityThreshold = 0.0005f),
            ) { v, _ -> position = v.coerceAtLeast(0f) }
        }
    }

    private fun drop(velocity: Float) {
        stop()
        job = scope.launch {
            animate(position, 1f + offscreen / travel, velocity, tween(200, easing = FastOutLinearInEasing)) { v, _ ->
                position = v
            }
            finish()
        }
    }

    private fun throwSideways(velocity: Float) {
        stop()
        val direction = if (offsetX != 0f) sign(offsetX) else sign(velocity)
        job = scope.launch {
            animate(offsetX, direction * width, velocity, tween(200, easing = FastOutLinearInEasing)) { v, _ ->
                offsetX = v
            }
            finish()
        }
    }

    private fun springBackSideways(velocity: Float) {
        stop()
        job = scope.launch {
            animate(offsetX, 0f, velocity, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                offsetX = v
            }
        }
    }

    private fun finish() {
        onDismissed()
        position = 1f
        offsetX = 0f
    }

    private fun stop() {
        job?.cancel()
        job = null
        entrance = 1f
    }

    private fun tick() {
        haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
}

@Composable
fun rememberWatchSheetState(): WatchSheetState {
    val scope = rememberCoroutineScope()
    return remember { WatchSheetState(scope) }
}

/**
 * The open video, drawn above the rest of the app. It is either the full watch page, or a
 * mini player docked above the tab bar while you browse, and the finger moves it smoothly
 * anywhere in between.
 *
 * The video is one composable that is only ever moved and resized, never rebuilt, so going
 * between the page, the mini player, full screen and picture-in-picture keeps playing
 * without a hitch.
 */
@Composable
fun WatchSheet(
    host: WatchHost,
    sheet: WatchSheetState,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val vm = host.watch ?: return
    val player = vm.player
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    SideEffect {
        sheet.haptics = haptics
        sheet.onDismissed = host::close
    }

    val context = LocalContext.current
    val activity = context.findActivity()
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val expanded by remember { derivedStateOf { sheet.position < 0.5f } }
    // The moment the video starts to shrink, its controls step aside.
    val shrinking by remember { derivedStateOf { sheet.position > 0.001f } }
    val pip = Pip.active
    val fullscreen = landscape && expanded && !pip

    // Full screen whenever the phone is sideways with the page open: hide the system bars.
    DisposableEffect(fullscreen) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }

    // The full-screen button forces an orientation. Once the phone is physically turned to match,
    // hand control back to the sensor so auto-rotate keeps working in both directions.
    val forced = remember { mutableStateOf<Int?>(null) }
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
        onDispose {
            listener.disable()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    // Turning the phone opens full screen only while the page is open; the mini player leaves
    // the rest of the app rotating as it normally does.
    LaunchedEffect(expanded) {
        if (!expanded) {
            forced.value = null
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else if (forced.value == null) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
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

    // Back leaves full screen first. On the page, it shrinks the video into the mini player,
    // following the back swipe as it goes on Android 14 and later.
    BackHandler(enabled = fullscreen) { force(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) }
    PredictiveBackHandler(enabled = expanded && !fullscreen && !pip) { events ->
        try {
            events.collect { sheet.backProgress(it.progress) }
            sheet.collapse()
        } catch (e: CancellationException) {
            sheet.expand()
            throw e
        }
    }

    KeepScreenOnWhilePlaying(player)
    ReportToPip(player)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.savePosition()
        player.pause()
    }
    // Shorts has its own player: the mini player pauses and steps out of the way.
    LaunchedEffect(WatchDock.shorts, expanded) {
        if (WatchDock.shorts && !expanded) player.pause()
    }

    // Animated values the layout reads directly, so they never recompose anything.
    val hidden = animateFloatAsState(
        if (WatchDock.shorts) 1f else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "dockHidden",
    )
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val dockInset = animateFloatAsState(
        (if (WatchDock.barHeight > 0) WatchDock.barHeight else navBottom).toFloat(),
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "dockInset",
    )
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    // Sideways, the open video always fills the screen (there's no page beside it), so expanding
    // from the mini player heads straight for full screen without changing course halfway.
    val full = landscape || pip

    val ready = vm.state as? WatchViewModel.State.Ready
    val thumbnail = vm.preview?.thumbnail ?: ready?.details?.video?.thumbnail
    val blocked = vm.state is WatchViewModel.State.Failed || vm.state is WatchViewModel.State.Locked
    val docked by remember { derivedStateOf { sheet.position >= 0.999f } }

    Layout(
        content = {
            key("page", vm.url) {
                // A new video from "Up next" fades its page in rather than popping.
                val fade = remember { Animatable(0f) }
                LaunchedEffect(Unit) { fade.animateTo(1f, tween(220)) }
                WatchPage(
                    vm = vm,
                    onClose = sheet::dismiss,
                    onOpenVideo = onOpenVideo,
                    onOpenChannel = onOpenChannel,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value }
                        .blockTouches(),
                )
            }
            MiniBar(
                vm = vm,
                active = docked,
                onClose = sheet::dismiss,
                modifier = Modifier.sheetGestures(sheet, fullscreen = false, onFullscreen = {}, onExitFullscreen = {}),
            )
            key("video", vm.url) {
                Box(
                    Modifier.sheetGestures(
                        sheet,
                        fullscreen = fullscreen,
                        onFullscreen = { force(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) },
                        onExitFullscreen = { force(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) },
                    ),
                ) {
                    VideoPlayer(
                        player = player,
                        thumbnail = thumbnail,
                        quality = if (pip) null else vm.quality,
                        error = vm.playbackError,
                        fullscreen = fullscreen || pip,
                        hdr = vm.hdr,
                        onToggleFullscreen = toggleFullscreen,
                        onRetry = vm::retryPlayback,
                        modifier = Modifier.fillMaxSize(),
                        pip = pip || shrinking,
                        unavailable = blocked,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val fw = w.toFloat()
        val fh = h.toFloat()

        val cardH = CARD_HEIGHT.dp.toPx()
        val margin = CARD_MARGIN.dp.toPx()
        val cardW = min(fw - 2 * margin, CARD_MAX_WIDTH.dp.toPx())
        val cardLeft = fw - margin - cardW
        val cardTop = fh - dockInset.value - margin - cardH
        val miniLeft = cardLeft + VIDEO_INSET.dp.toPx()
        val miniTop = cardTop + VIDEO_INSET.dp.toPx()
        val miniW = MINI_VIDEO_WIDTH.dp.toPx()
        val miniH = MINI_VIDEO_HEIGHT.dp.toPx()

        // The video's rectangle on the full page (or the whole screen when full screen).
        val pageTop = if (full) 0f else statusTop
        val pageH = if (full) fh else fw * 9f / 16f

        sheet.travel = (miniTop - pageTop).coerceAtLeast(1f)
        sheet.width = fw
        sheet.swipeAway = cardH * 0.6f
        sheet.offscreen = fh - cardTop
        sheet.fling = 900.dp.toPx()

        // Everything below is plain arithmetic on the sheet's position: no state is written.
        val g = if (pip) 0f else sheet.progress
        val below = if (pip) 0f else ((sheet.position - 1f) * sheet.travel).coerceAtLeast(0f)
        val dx = if (pip) 0f else sheet.offsetX
        val hide = hidden.value * g
        // Fades as the mini player is swiped down or sideways, or steps aside for Shorts.
        val presence = (1f - below / (cardH * 1.5f)).coerceIn(0f, 1f) *
            (1f - abs(dx) / (fw * 0.8f)).coerceIn(0f, 1f) *
            (1f - hide)
        val drop = below + hide * (cardH + margin)

        val videoW = lerp(fw, miniW, g)
        val videoH = lerp(pageH, miniH, g)
        val videoX = lerp(0f, miniLeft, g) + dx
        val videoY = lerp(pageTop, miniTop, g) + drop

        val page = measurables[0].measure(Constraints.fixed(w, h))
        val card = measurables[1].measure(Constraints.fixed(cardW.roundToInt(), cardH.roundToInt()))
        val video = measurables[2].measure(
            Constraints.fixed(videoW.roundToInt().coerceAtLeast(1), videoH.roundToInt().coerceAtLeast(1)),
        )
        val corner = MINI_CORNER.dp.toPx()
        val slide = fh * 0.12f

        layout(w, h) {
            // Pieces that can't be seen aren't placed, so they take no touches and cost nothing to draw.
            if (!full && g < 0.999f) {
                page.placeWithLayer(0, 0) {
                    // The page travels down with the video and fades out on the way.
                    translationY = g * sheet.travel + (1f - sheet.entrance) * slide
                    alpha = (1f - g / 0.6f).coerceIn(0f, 1f) * sheet.entrance
                }
            }
            if (!full && g > 0.6f && presence > 0.01f) {
                card.placeWithLayer(cardLeft.roundToInt(), cardTop.roundToInt()) {
                    translationX = dx
                    translationY = drop
                    alpha = ((g - 0.75f) / 0.25f).coerceIn(0f, 1f) * presence
                }
            }
            if (presence > 0.01f) {
                video.placeWithLayer(videoX.roundToInt(), videoY.roundToInt()) {
                    translationY = (1f - sheet.entrance) * slide
                    alpha = presence * sheet.entrance
                    val radius = corner * g
                    if (radius > 0f) {
                        shape = RoundedCornerShape(radius)
                        clip = true
                    }
                }
            }
        }
    }
}

/** The docked bar: title, channel, play/pause and close, with a thin progress line. */
@Composable
private fun MiniBar(vm: WatchViewModel, active: Boolean, onClose: () -> Unit, modifier: Modifier) {
    val player = vm.player
    val video = (vm.state as? WatchViewModel.State.Ready)?.details?.video ?: vm.preview
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val update = { playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = update()
        }
        player.addListener(listener)
        update()
        onDispose { player.removeListener(listener) }
    }
    // Only ticks while the bar is on screen. Drawn in the draw phase, so an update repaints one line.
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player, active) {
        while (active) {
            val duration = player.duration
            fraction = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier
            .shadow(10.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = (VIDEO_INSET + MINI_VIDEO_WIDTH + 12f).dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    video?.title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    video?.channelName.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (playing) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind { drawRect(accent, size = Size(size.width * fraction, size.height)) },
        )
    }
}

/**
 * Drags on the video (and on the mini player) move the sheet.
 *
 * Movement is added up from each event's change rather than read as a position, because the
 * box being dragged is itself moving under the finger; a position measured against it would
 * barely change, and the drag and its fling speed would come out wrong.
 */
private fun Modifier.sheetGestures(
    sheet: WatchSheetState,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
): Modifier = pointerInput(sheet, fullscreen) {
    val slop = viewConfiguration.touchSlop
    val flick = 40.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startedDocked = sheet.position >= 0.5f
        sheet.grab()
        val tracker = VelocityTracker()
        var total = Offset.Zero
        tracker.addPosition(down.uptimeMillis, total)
        var mode = Gesture.Undecided
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                when (mode) {
                    Gesture.Vertical -> sheet.release(true, tracker.calculateVelocity(), startedDocked)
                    Gesture.Sideways -> sheet.release(false, tracker.calculateVelocity(), startedDocked)
                    Gesture.Undecided -> if (startedDocked && change != null && !change.isConsumed) {
                        // A tap on the mini player opens the page.
                        change.consume()
                        sheet.expand()
                    } else {
                        sheet.resume()
                    }
                    else -> sheet.resume()
                }
                break
            }
            if (mode == Gesture.Ignored) continue
            if (mode == Gesture.Undecided && change.isConsumed) {
                // Something inside (like the seek bar) took this touch.
                mode = Gesture.Ignored
                sheet.resume()
                continue
            }
            val delta = change.positionChangeIgnoreConsumed()
            total += delta
            tracker.addPosition(change.uptimeMillis, total)

            when (mode) {
                Gesture.Undecided -> {
                    val distance = total.getDistance()
                    if (distance < slop) continue
                    val vertical = abs(total.y) >= abs(total.x)
                    mode = when {
                        fullscreen -> if (vertical && total.y > 0) Gesture.FlickDown else Gesture.Ignored
                        !startedDocked && !vertical -> Gesture.Ignored
                        // Swiping up on the full page's video goes full screen, like YouTube.
                        !startedDocked && total.y < 0 -> if (sheet.position <= 0f) Gesture.FlickUp else Gesture.Vertical
                        vertical -> Gesture.Vertical
                        else -> Gesture.Sideways
                    }
                    if (mode == Gesture.Ignored) {
                        sheet.resume()
                        continue
                    }
                    change.consume()
                    // Start from where the finger crossed the slop, so nothing jumps.
                    val past = total * ((distance - slop) / distance)
                    if (mode == Gesture.Vertical) sheet.dragBy(past.y)
                    if (mode == Gesture.Sideways) sheet.dragSideways(past.x)
                }
                Gesture.Vertical -> {
                    change.consume()
                    sheet.dragBy(delta.y)
                }
                Gesture.Sideways -> {
                    change.consume()
                    sheet.dragSideways(delta.x)
                }
                Gesture.FlickUp -> {
                    change.consume()
                    if (total.y < -flick) {
                        onFullscreen()
                        mode = Gesture.Done
                    }
                }
                Gesture.FlickDown -> {
                    change.consume()
                    if (total.y > flick) {
                        onExitFullscreen()
                        mode = Gesture.Done
                    }
                }
                Gesture.Done -> change.consume()
                Gesture.Ignored -> Unit
            }
        }
    }
}

private enum class Gesture { Undecided, Vertical, Sideways, FlickUp, FlickDown, Done, Ignored }

/** Keeps touches on the page from reaching the screen underneath it. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction
