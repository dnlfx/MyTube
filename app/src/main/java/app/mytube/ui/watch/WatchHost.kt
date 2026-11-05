package app.mytube.ui.watch

import android.app.Application
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import app.mytube.player.PlayerFactory

/**
 * Owns the one video player the whole app shares, and the page of the video in it.
 *
 * The player lives as long as the app does, so shrinking the video into the mini player,
 * expanding it again, rotating, or switching to another video never builds a new player or
 * throws away what it has buffered. Each video's page (details, comments, suggestions) gets
 * its own [WatchViewModel], which is cleared when another video replaces it.
 */
class WatchHost(app: Application) : AndroidViewModel(app) {

    private var created: ExoPlayer? = null
    private var session: MediaSession? = null

    /**
     * True while the app is on screen, including as the picture-in-picture window. Video only
     * plays then: Android 17 silences audio from apps in the background that aren't running a
     * media service, so starting playback there would play a muted video nobody can see.
     */
    private var onScreen = false
    /** A video finished loading while the app was hidden; start it once the app is back. */
    private var playWhenShown = false

    /** Built on first use, so opening the app doesn't spend time on a player it may not need. */
    val player: ExoPlayer
        get() = created ?: PlayerFactory.create(getApplication()).also { player ->
            created = player
            // Lets headphone buttons, the lock screen and the picture-in-picture window control
            // playback. They go through a gate that ignores "play" while the app is hidden, so a
            // Bluetooth button press can't start a video nobody can see or hear.
            val gated = object : ForwardingPlayer(player) {
                override fun play() {
                    if (onScreen) super.play()
                }

                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    if (onScreen || !playWhenReady) super.setPlayWhenReady(playWhenReady)
                }
            }
            session = runCatching {
                MediaSession.Builder(getApplication(), gated).setId("watch").build()
            }.getOrNull()
        }

    /** The app came on screen, or back from the background. */
    fun onShown() {
        onScreen = true
        if (playWhenShown) {
            playWhenShown = false
            created?.play()
        }
    }

    /** The app went to the background (the picture-in-picture window counts as on screen). */
    fun onHidden() {
        onScreen = false
        watch?.savePosition()
        created?.pause()
    }

    /** Starts the loaded video now if the app is visible, otherwise as soon as it is. */
    fun startPlayback() {
        if (onScreen) player.playWhenReady = true else playWhenShown = true
    }

    private var store: ViewModelStore? = null

    /** The open video, or null when nothing is playing. */
    var watch by mutableStateOf<WatchViewModel?>(null)
        private set

    /** Shows [url] in the player. Returns false if it was already the open video. */
    fun open(url: String): Boolean {
        if (watch?.url == url) return false
        // Clearing the old page saves where it got to before the player moves on.
        store?.clear()
        playWhenShown = false
        val player = player
        player.stop()
        player.clearMediaItems()
        val next = ViewModelStore()
        store = next
        val app = getApplication<Application>()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WatchViewModel(app, url, player, ::startPlayback) as T
        }
        watch = ViewModelProvider(next, factory)[WatchViewModel::class.java]
        return true
    }

    /** Stops playback and closes the video. The player stays ready for the next one. */
    fun close() {
        store?.clear()
        store = null
        watch = null
        playWhenShown = false
        created?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    override fun onCleared() {
        store?.clear()
        session?.release()
        created?.release()
    }
}

/** What the rest of the app tells the mini player about the screen around it. */
object WatchDock {
    /** Height in pixels of the bottom tab bar while it is on screen, so the mini player sits above it. */
    var barHeight by mutableIntStateOf(0)
    /** True while Shorts is open: the mini player steps aside and pauses, like YouTube's. */
    var shorts by mutableStateOf(false)
}

/** Extra room at the end of lists so the mini player never covers the last row. */
val LocalDockPadding = compositionLocalOf { 0.dp }

/** Space a docked mini player takes up: its height plus the gaps around it. */
val DOCK_PADDING = 80.dp
