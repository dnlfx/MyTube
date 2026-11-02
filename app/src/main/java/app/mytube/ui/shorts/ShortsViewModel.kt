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
import androidx.media3.exoplayer.source.MediaSource
import app.mytube.data.ChannelRef
import app.mytube.data.Shorts
import app.mytube.data.ShortsHistory
import app.mytube.data.Video
import app.mytube.data.YouTube
import app.mytube.player.MediaSources
import app.mytube.player.PlayerFactory
import app.mytube.player.YouTubeDataSource
import app.mytube.ui.search.friendlyMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

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

    /**
     * Two players take turns. While one plays the Short on screen, the other has the next Short
     * loaded and buffered, so swiping up starts it instantly instead of waiting for the network.
     */
    private inner class Slot {
        var url: String? = null
        val player: ExoPlayer = PlayerFactory.create(getApplication<Application>(), shortForm = true).apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) = onSlotError(this@Slot)

                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (this@Slot === active && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) loops++
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (this@Slot === active && state == Player.STATE_READY) durationMs = duration.coerceAtLeast(0)
                }
            })
        }

        fun load(url: String, source: MediaSource) {
            this.url = url
            player.playWhenReady = false
            player.setPlaybackSpeed(1f)
            player.setMediaSource(source)
            player.prepare()
        }

        fun clear() {
            url = null
            player.stop()
            player.clearMediaItems()
        }
    }

    private val slots = listOf(Slot(), Slot())
    private var active = slots[0]

    /** The player for the Short on screen. Changes as the two players take turns. */
    var player: ExoPlayer by mutableStateOf(active.player)
        private set

    private val dataSource = YouTubeDataSource.factory()
    // Loading a Short's page takes a request, so the next few are fetched while you watch.
    private val pages = HashMap<String, Deferred<StreamInfo>>()
    // Shorts whose player failed once; the next failure is shown instead of retried.
    private val retried = HashSet<String>()
    private var playJob: Job? = null
    private var preloadJob: Job? = null

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
        playJob?.cancel()
        preloadJob?.cancel()
        slots.forEach { it.clear() }
        playingUrl = null
        state = State.Loading
        viewModelScope.launch {
            val shorts = try {
                Shorts.recommend(force = force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.Failed(e.friendlyMessage())
                return@launch
            }
            current = 0
            pages.clear()
            retried.clear()
            state = if (shorts.isEmpty()) State.Empty else State.Ready(shorts)
            // Start fetching the first few right away rather than one at a time.
            shorts.take(3).forEach { page(it.url) }
            onPageShown(0)
        }
    }

    /** Adds another batch of picks, chosen with everything you've watched since the last one. */
    fun loadMore() {
        val ready = state as? State.Ready ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val have = ready.shorts.mapTo(HashSet()) { it.url }
                val more = try {
                    Shorts.recommend(exclude = have)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val latest = state as? State.Ready
                if (latest != null && more.isNotEmpty()) {
                    state = State.Ready(latest.shorts + more.filter { it.url !in have })
                }
            } finally {
                loadingMore = false
            }
        }
    }

    fun onPageShown(index: Int) {
        val shorts = (state as? State.Ready)?.shorts ?: return
        val short = shorts.getOrNull(index) ?: return
        recordWatch()
        current = index
        error = null
        playJob?.cancel()
        player.pause()
        if (index >= shorts.size - 6) loadMore()

        // Already loaded by the other player while you watched the previous one.
        slots.firstOrNull { it.url == short.url }?.let { ready ->
            show(ready, short)
            preloadNext(index)
            return
        }

        playingUrl = null
        playJob = viewModelScope.launch {
            val source = try {
                source(short.url)
            } catch (e: CancellationException) {
                // You swiped on before this one loaded; the new page takes over.
                throw e
            } catch (e: Exception) {
                pages.remove(short.url)
                if (current == index) error = e.friendlyMessage()
                return@launch
            }
            if (current != index) return@launch
            // Keep the player that holds the next Short, if either does, and use the other.
            val next = shorts.getOrNull(index + 1)?.url
            val slot = slots.firstOrNull { it.url != next && it !== active }
                ?: slots.firstOrNull { it.url != next }
                ?: active
            slot.load(short.url, source)
            show(slot, short)
            preloadNext(index)
        }
    }

    fun retry() {
        val url = (state as? State.Ready)?.shorts?.getOrNull(current)?.url
        if (url != null) {
            pages.remove(url)
            YouTube.forget(url)
            slots.filter { it.url == url }.forEach { it.clear() }
        }
        onPageShown(current)
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun toggleLike(video: Video) = ShortsHistory.toggleLike(video, related[video.url].orEmpty())

    /** Puts [slot]'s Short on screen and starts it from the beginning. */
    private fun show(slot: Slot, short: Video) {
        slots.forEach { if (it !== slot) it.player.pause() }
        active = slot
        player = slot.player
        slot.player.seekTo(0)
        playingUrl = short.url
        watching = short
        loops = 0
        startPos = 0
        durationMs = slot.player.duration.coerceAtLeast(0)
        if (visible) slot.player.play()
    }

    /** Loads the Short after [index] into the idle player, and fetches the one after that. */
    private fun preloadNext(index: Int) {
        val shorts = (state as? State.Ready)?.shorts ?: return
        shorts.getOrNull(index + 2)?.let { page(it.url) }
        val next = shorts.getOrNull(index + 1) ?: return
        val idle = slots.first { it !== active }
        if (idle.url == next.url) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            val source = try {
                source(next.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // It'll be retried, and any error shown, if you swipe to it.
                pages.remove(next.url)
                return@launch
            }
            if (current == index && idle !== active) idle.load(next.url, source)
        }
    }

    private fun onSlotError(slot: Slot) {
        val url = slot.url ?: return
        pages.remove(url)
        YouTube.forget(url)
        slot.clear()
        if (slot !== active) return
        playingUrl = null
        // Video links sometimes expire or get refused over a VPN; one fresh try usually fixes it.
        if (retried.add(url)) {
            onPageShown(current)
        } else {
            error = "Couldn't play this Short. Tap to retry."
        }
    }

    /** Saves how much of the current Short you watched, then starts counting afresh. */
    private fun recordWatch() {
        val video = watching ?: return
        val duration = durationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0)
        val watched = loops * duration + player.currentPosition.coerceAtLeast(0) - startPos
        ShortsHistory.record(video, watched, duration, related[video.url].orEmpty())
        watching = null
        loops = 0
    }

    /** Starts loading a Short's page, or returns the load already under way. */
    private fun page(url: String): Deferred<StreamInfo> =
        pages.getOrPut(url) {
            viewModelScope.async {
                YouTube.stream(url).also { related[url] = Shorts.relatedChannels(it) }
            }
        }

    // Each player needs its own copy of the media source, so it's built fresh every time.
    private suspend fun source(url: String): MediaSource {
        val info = page(url).await()
        return withContext(Dispatchers.Default) { MediaSources.build(info, dataSource, allowHdr = false).source }
    }

    override fun onCleared() {
        recordWatch()
        slots.forEach { it.player.release() }
    }
}
