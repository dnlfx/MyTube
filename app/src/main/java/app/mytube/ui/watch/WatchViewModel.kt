package app.mytube.ui.watch

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.mytube.data.Comment
import app.mytube.data.CommentsPage
import app.mytube.data.Feedback
import app.mytube.data.History
import app.mytube.data.Suggestions
import app.mytube.data.Video
import app.mytube.data.VideoDetails
import app.mytube.data.YouTube
import app.mytube.data.toDetails
import app.mytube.player.MediaSources
import app.mytube.player.YouTubeDataSource
import app.mytube.ui.search.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One video's page: its details, comments and suggestions, and what it tells the shared [player]
 * to play. [WatchHost] creates one per video and clears it when the next video replaces it.
 */
class WatchViewModel(app: Application, val url: String, val player: ExoPlayer) : AndroidViewModel(app) {

    sealed interface State {
        data object Loading : State
        data class Ready(val details: VideoDetails) : State
        data class Failed(val message: String) : State
        /** Needs a channel membership or a purchase; trying again won't help. */
        data class Locked(val paid: Boolean) : State
    }

    /** What we already know from the list the user tapped, shown while the full page loads. */
    val preview: Video? = YouTube.preview(url)

    var state by mutableStateOf<State>(State.Loading)
        private set
    var quality by mutableStateOf<String?>(null)
        private set
    /** HDR needs a SurfaceView; everything else uses a TextureView, which can move and scale smoothly. */
    var hdr by mutableStateOf(false)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    sealed interface CommentsState {
        data object Idle : CommentsState
        data object Loading : CommentsState
        data class Loaded(val comments: List<Comment>, val disabled: Boolean, val loadingMore: Boolean) : CommentsState
        data class Failed(val message: String) : CommentsState
    }

    /** The open tab under the video; lives here so rotating the phone doesn't reset it. */
    var tab by mutableStateOf(WatchTab.COMMENTS)

    enum class CommentSort(val label: String) { LIKES("Most liked"), NEWEST("Newest") }

    var commentSort by mutableStateOf(CommentSort.LIKES)
        private set

    fun sortComments(sort: CommentSort) {
        commentSort = sort
        // YouTube only hands out comments in its own ranked order, so "Newest" is sorted from what
        // has loaded. Pull in a few more pages first so the newest ones are likely among them.
        if (sort == CommentSort.NEWEST) viewModelScope.launch {
            repeat(4) {
                val loaded = comments as? CommentsState.Loaded ?: return@launch
                if (commentsPage?.hasMore != true) return@launch
                while ((comments as? CommentsState.Loaded)?.loadingMore == true) kotlinx.coroutines.delay(100)
                loadMoreComments()
                while ((comments as? CommentsState.Loaded)?.loadingMore == true) kotlinx.coroutines.delay(100)
                if ((comments as? CommentsState.Loaded)?.comments?.size == loaded.comments.size) return@launch
            }
        }
    }

    var comments by mutableStateOf<CommentsState>(CommentsState.Idle)
        private set
    private var commentsPage: CommentsPage? = null

    /** Picks from the user's history, same as the home page, minus this video. */
    var suggested by mutableStateOf<List<Video>>(emptyList())
        private set

    private val errors = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Video links expire, and switching VPN servers changes our IP, which also
            // invalidates them. Fetch fresh links once before showing an error.
            if (!autoRetried) {
                autoRetried = true
                retryPlayback()
            } else {
                playbackError = "Playback failed (${error.errorCodeName}). Tap to retry."
            }
        }
    }

    private val dataSource = YouTubeDataSource.factory()
    private var autoRetried = false

    init {
        player.addListener(errors)
        load()
        loadComments()
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(History.entries, Feedback.state) { e, f -> e to f }
                .collect { (entries, feedback) ->
                    suggested = withContext(Dispatchers.Default) {
                        Suggestions.build(entries, exclude = setOf(url), limit = 40, feedback = feedback)
                    }
                }
        }
        // Save the position regularly so it survives the app being closed or killed.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                if (player.isPlaying) savePosition()
            }
        }
    }

    fun loadComments() {
        if (comments !is CommentsState.Idle && comments !is CommentsState.Failed) return
        comments = CommentsState.Loading
        viewModelScope.launch {
            comments = runCatching { YouTube.comments(url) }.fold(
                onSuccess = { commentsPage = it; CommentsState.Loaded(it.comments, it.disabled, false) },
                onFailure = { CommentsState.Failed(it.friendlyMessage()) },
            )
        }
    }

    fun loadMoreComments() {
        val current = comments as? CommentsState.Loaded ?: return
        val page = commentsPage ?: return
        if (current.loadingMore || !page.hasMore) return
        comments = current.copy(loadingMore = true)
        viewModelScope.launch {
            val next = runCatching { YouTube.moreComments(page) }.getOrNull()
            val loaded = comments as? CommentsState.Loaded ?: return@launch
            if (next != null) commentsPage = next
            comments = loaded.copy(comments = loaded.comments + next?.comments.orEmpty(), loadingMore = false)
        }
    }

    fun load() {
        autoRetried = false
        state = State.Loading
        playbackError = null
        viewModelScope.launch {
            val info = try {
                YouTube.stream(url)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                state = if (e.isMembersOnly()) State.Locked(paid = false)
                else if (e is org.schabi.newpipe.extractor.exceptions.PaidContentException) State.Locked(paid = true)
                else State.Failed(e.friendlyMessage())
                return@launch
            }
            // Show the page first; the player keeps its spinner while streams are prepared.
            val details = info.toDetails()
            YouTube.remember(details.related)
            state = State.Ready(details)
            History.record(details.video, details.related)

            val playback = runCatching {
                withContext(Dispatchers.Default) { MediaSources.build(info, dataSource) }
            }.getOrElse {
                playbackError = it.friendlyMessage()
                return@launch
            }
            quality = playback.qualityLabel
            hdr = playback.hdr
            val resumeAt = History.position(url).takeIf { pos ->
                pos > 10_000 && (details.video.durationSeconds <= 0 || pos < details.video.durationSeconds * 1000 - 15_000)
            } ?: 0L
            player.setMediaSource(playback.source, resumeAt)
            player.prepare()
            player.playWhenReady = true
        }
    }


    fun retryPlayback() {
        val position = player.currentPosition
        playbackError = null
        YouTube.forget(url)
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

    fun savePosition() {
        if (state !is State.Ready) return
        val duration = player.duration
        // A finished video starts over next time.
        val ended = player.playbackState == Player.STATE_ENDED ||
            (duration > 0 && player.currentPosition > duration - 15_000)
        History.savePosition(url, if (ended) 0 else player.currentPosition)
        if (duration > 0) History.recordProgress(url, if (ended) 1f else player.currentPosition.toFloat() / duration)
    }

    override fun onCleared() {
        savePosition()
        // The player is shared and carries on with the next video; only this page's hold on it ends.
        player.removeListener(errors)
    }
}

/** YouTube refused the video because it's for paying channel members only. */
private fun Throwable.isMembersOnly(): Boolean =
    this is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException &&
        message?.contains("members", ignoreCase = true) == true
