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
import app.mytube.data.ChannelRef
import app.mytube.data.Shorts
import app.mytube.data.ShortsHistory
import app.mytube.data.Video
import app.mytube.data.YouTube
import app.mytube.player.MediaSources
import app.mytube.player.Playback
import app.mytube.player.PlayerFactory
import app.mytube.player.YouTubeDataSource
import app.mytube.ui.search.friendlyMessage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    var loadingMore by mutableStateOf(false)
        private set

    // How long the current Short has been watched, counting every replay, to learn what you like.
    private var watching: Video? = null
    private var loops = 0
    private var startPos = 0L
    private var durationMs = 0L
    private val related = HashMap<String, List<ChannelRef>>()

    val player: ExoPlayer = PlayerFactory.create(app).apply {
        repeatMode = Player.REPEAT_MODE_ONE
        addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val url = playingUrl ?: return
                prepared.remove(url)
                YouTube.forget(url)
                error = "Couldn't play this Short. Tap to retry."
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) loops++
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = duration.coerceAtLeast(0)
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
            if (value) {
                val url = playingUrl
                if (url != null && error == null) {
                    // Coming back to the tab: keep counting from where the video is now.
                    watching = (state as? State.Ready)?.shorts?.firstOrNull { it.url == url }
                    loops = 0
                    startPos = player.currentPosition
                    player.play()
                }
            } else {
                player.pause()
                recordWatch()
            }
        }

    init { load() }

    fun load(force: Boolean = false) {
        recordWatch()
        state = State.Loading
        viewModelScope.launch {
            val shorts = runCatching { Shorts.recommend(force = force) }.getOrElse {
                state = State.Failed(it.friendlyMessage())
                return@launch
            }
            current = 0
            playingUrl = null
            prepared.clear()
            state = if (shorts.isEmpty()) State.Empty else State.Ready(shorts)
            onPageShown(0)
        }
    }

    /** Adds another batch of picks, chosen with everything you've watched since the last one. */
    fun loadMore() {
        val ready = state as? State.Ready ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            val have = ready.shorts.mapTo(HashSet()) { it.url }
            val more = runCatching { Shorts.recommend(exclude = have) }.getOrDefault(emptyList())
            val latest = state as? State.Ready
            if (latest != null && more.isNotEmpty()) state = State.Ready(latest.shorts + more.filter { it.url !in have })
            loadingMore = false
        }
    }

    fun onPageShown(index: Int) {
        val shorts = (state as? State.Ready)?.shorts ?: return
        val short = shorts.getOrNull(index) ?: return
        recordWatch()
        current = index
        error = null
        playJob?.cancel()
        player.stop()
        playingUrl = null
        if (index >= shorts.size - 6) loadMore()
        playJob = viewModelScope.launch {
            val playback = runCatching { prepare(short.url).await() }.getOrElse {
                prepared.remove(short.url)
                error = it.friendlyMessage()
                return@launch
            }
            player.setMediaSource(playback.source)
            player.prepare()
            playingUrl = short.url
            watching = short
            loops = 0
            startPos = 0
            durationMs = 0
            if (visible) player.play()
            shorts.getOrNull(index + 1)?.let { prepare(it.url) }
        }
    }

    fun retry() = onPageShown(current)

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun toggleLike(video: Video) = ShortsHistory.toggleLike(video, related[video.url].orEmpty())

    /** Saves how much of the current Short you watched, then starts counting afresh. */
    private fun recordWatch() {
        val video = watching ?: return
        val duration = durationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0)
        val watched = loops * duration + player.currentPosition.coerceAtLeast(0) - startPos
        ShortsHistory.record(video, watched, duration, related[video.url].orEmpty())
        watching = null
        loops = 0
    }

    private fun prepare(url: String): Deferred<Playback> =
        prepared.getOrPut(url) {
            viewModelScope.async {
                val info = YouTube.stream(url)
                related[url] = Shorts.relatedChannels(info)
                withContext(Dispatchers.Default) { MediaSources.build(info, dataSource, allowHdr = false) }
            }
        }

    override fun onCleared() {
        recordWatch()
        player.release()
    }
}
