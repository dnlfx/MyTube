package app.mytube.ui.shorts

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.mytube.data.History
import app.mytube.data.Shorts
import app.mytube.data.Subscriptions
import app.mytube.data.Video
import app.mytube.data.YouTube
import app.mytube.player.MediaSources
import app.mytube.player.Playback
import app.mytube.player.PlayerFactory
import app.mytube.player.YouTubeDataSource
import app.mytube.ui.search.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortsViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Loading : State
        data class Ready(val shorts: List<Video>) : State
        data object Empty : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set
    /** Which Short is on screen. */
    var current by mutableIntStateOf(0)
        private set
    /** The Short the player has loaded, so other pages keep showing their thumbnail. */
    var playingUrl by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val player: ExoPlayer = PlayerFactory.create(app).apply {
        repeatMode = Player.REPEAT_MODE_ONE
        addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val url = playingUrl ?: return
                prepared.remove(url)
                YouTube.forget(url)
                error = "Couldn't play this Short. Tap to retry."
            }
        })
    }
    private val dataSource = YouTubeDataSource.factory()
    // Building a Short's streams takes a request, so the next one is prepared while you watch.
    private val prepared = HashMap<String, Deferred<Playback>>()
    private var playJob: Job? = null
    /** False while the Shorts tab is hidden, so a finished load doesn't start playing behind it. */
    var visible = false
        set(value) {
            field = value
            if (value) { if (playingUrl != null && error == null) player.play() } else player.pause()
        }

    init { load() }

    fun load(force: Boolean = false) {
        state = State.Loading
        viewModelScope.launch {
            val channels = Shorts.sourceChannels(Subscriptions.channels.value, History.entries.value)
            if (channels.isEmpty()) {
                state = State.Empty
                return@launch
            }
            val shorts = runCatching { Shorts.feed(channels, force) }.getOrElse {
                state = State.Failed(it.friendlyMessage())
                return@launch
            }
            state = if (shorts.isEmpty()) State.Empty else State.Ready(shorts)
            current = 0
            playingUrl = null
            prepared.clear()
            onPageShown(0)
        }
    }

    fun onPageShown(index: Int) {
        val shorts = (state as? State.Ready)?.shorts ?: return
        val short = shorts.getOrNull(index) ?: return
        current = index
        error = null
        playJob?.cancel()
        player.stop()
        playingUrl = null
        playJob = viewModelScope.launch {
            val playback = runCatching { prepare(short.url).await() }.getOrElse {
                prepared.remove(short.url)
                error = it.friendlyMessage()
                return@launch
            }
            player.setMediaSource(playback.source)
            player.prepare()
            playingUrl = short.url
            if (visible) player.play()
            shorts.getOrNull(index + 1)?.let { prepare(it.url) }
        }
    }

    fun retry() = onPageShown(current)

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun prepare(url: String): Deferred<Playback> =
        prepared.getOrPut(url) {
            viewModelScope.async {
                val info = YouTube.stream(url)
                withContext(Dispatchers.Default) { MediaSources.build(info, dataSource, allowHdr = false) }
            }
        }

    override fun onCleared() {
        player.release()
    }
}
