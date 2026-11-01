package app.mytube.ui.watch

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.mytube.data.Video
import app.mytube.data.VideoDetails
import app.mytube.data.YouTube
import app.mytube.data.toDetails
import app.mytube.player.MediaSources
import app.mytube.player.PlayerFactory
import app.mytube.player.YouTubeDataSource
import app.mytube.ui.search.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {

    val url: String = saved.get<String>("url").orEmpty()

    sealed interface State {
        data object Loading : State
        data class Ready(val details: VideoDetails) : State
        data class Failed(val message: String) : State
    }

    /** What we already know from the list the user tapped, shown while the full page loads. */
    val preview: Video? = YouTube.preview(url)

    var state by mutableStateOf<State>(State.Loading)
        private set
    var quality by mutableStateOf<String?>(null)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    val player: ExoPlayer = PlayerFactory.create(app).apply {
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Playback failed (${error.errorCodeName}). Tap to retry."
            }
        })
    }

    private val dataSource = YouTubeDataSource.factory()

    init {
        load()
    }

    fun load() {
        state = State.Loading
        playbackError = null
        viewModelScope.launch {
            runCatching {
                val info = YouTube.stream(url)
                val playback = withContext(Dispatchers.Default) { MediaSources.build(info, dataSource) }
                info to playback
            }.onSuccess { (info, playback) ->
                val details = info.toDetails()
                YouTube.remember(details.related)
                state = State.Ready(details)
                quality = playback.qualityLabel
                player.setMediaSource(playback.source)
                player.prepare()
                player.playWhenReady = true
            }.onFailure {
                state = State.Failed(it.friendlyMessage())
            }
        }
    }

    fun retryPlayback() {
        val position = player.currentPosition
        playbackError = null
        viewModelScope.launch {
            runCatching {
                val info = YouTube.stream(url)
                withContext(Dispatchers.Default) { MediaSources.build(info, dataSource) }
            }.onSuccess {
                quality = it.qualityLabel
                player.setMediaSource(it.source, position)
                player.prepare()
                player.playWhenReady = true
            }.onFailure { playbackError = it.friendlyMessage() }
        }
    }

    override fun onCleared() {
        player.release()
    }
}
