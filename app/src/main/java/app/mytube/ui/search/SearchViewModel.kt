package app.mytube.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mytube.data.SearchPage
import app.mytube.data.Video
import app.mytube.data.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

class SearchViewModel : ViewModel() {

    sealed interface Results {
        data object Idle : Results
        data object Loading : Results
        data class Loaded(val videos: List<Video>, val loadingMore: Boolean) : Results
        data class Failed(val message: String) : Results
    }

    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var results by mutableStateOf<Results>(Results.Idle)
        private set
    var channels by mutableStateOf<List<app.mytube.data.Channel>>(emptyList())
        private set
    /** True while the user is typing, so suggestions show instead of results. */
    var editing by mutableStateOf(true)
        private set

    private var page: SearchPage? = null
    private val backfillLimit = kotlinx.coroutines.sync.Semaphore(4)
    // Videos whose count is being fetched now, so two searches don't fetch the same one twice.
    private val backfilling = HashSet<String>()

    /**
     * Some results arrive without a view count. Counts we've seen before are filled in at once;
     * the rest are fetched in the background, in the order they're shown, and slotted in. A
     * fetch that fails (often a VPN hiccup) is tried once more after a short pause.
     */
    private fun backfillViews(videos: List<Video>) {
        videos.filter { it.viewCount < 0 && !it.isLive && YouTube.knownViews(it.url) == null && backfilling.add(it.url) }
            .forEach { video ->
                viewModelScope.launch {
                    try {
                        var views: Long? = null
                        for (attempt in 1..2) {
                            views = backfillLimit.withPermit {
                                try {
                                    YouTube.viewCount(video.url)
                                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (views != null) break
                            delay(2_000)
                        }
                        val count = views?.takeIf { it >= 0 } ?: return@launch
                        val loaded = results as? Results.Loaded ?: return@launch
                        results = loaded.copy(
                            videos = loaded.videos.map { if (it.url == video.url) it.copy(viewCount = count) else it },
                        )
                    } finally {
                        backfilling.remove(video.url)
                    }
                }
            }
    }
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        query = value
        editing = true
        suggestJob?.cancel()
        if (value.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(180)
            suggestions = YouTube.suggestions(value).take(8)
        }
    }

    fun onEditingChange(value: Boolean) {
        editing = value
    }

    fun submit(value: String = query) {
        val q = value.trim()
        if (q.isEmpty()) return
        query = q
        editing = false
        app.mytube.data.SearchHistory.add(q)
        suggestJob?.cancel()
        searchJob?.cancel()
        results = Results.Loading
        channels = emptyList()
        searchJob = viewModelScope.launch {
            launch { channels = runCatching { YouTube.searchChannels(q) }.getOrDefault(emptyList()) }
            val found = try {
                YouTube.search(q)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                results = Results.Failed(e.friendlyMessage())
                return@launch
            }
            page = found
            val videos = YouTube.withKnownViews(found.videos)
            results = Results.Loaded(videos, loadingMore = false)
            backfillViews(videos)
        }
    }

    fun loadMore() {
        val current = results as? Results.Loaded ?: return
        val p = page ?: return
        if (current.loadingMore || !p.hasMore) return
        results = current.copy(loadingMore = true)
        viewModelScope.launch {
            val next = runCatching { YouTube.more(p) }.getOrNull()
            val loaded = results as? Results.Loaded ?: return@launch
            if (next == null) {
                results = loaded.copy(loadingMore = false)
                return@launch
            }
            page = next
            val videos = YouTube.withKnownViews(next.videos)
            val seen = loaded.videos.mapTo(HashSet()) { it.url }
            results = Results.Loaded(loaded.videos + videos.filter { it.url !in seen }, loadingMore = false)
            backfillViews(videos)
        }
    }
}

fun Throwable.friendlyMessage(): String = when (this) {
    is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException ->
        "YouTube flagged this VPN server as a bot. Switch to a different VPN server or city, then try again."
    is org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException ->
        "This video isn't available in your VPN's country. Try a server in another country."
    is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
        "Couldn't reach YouTube. Check your connection and try again."
    is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ->
        "YouTube is asking for a captcha. Wait a few minutes and try again."
    is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ->
        "This video is age-restricted and can't be played without signing in."
    is org.schabi.newpipe.extractor.exceptions.PaidContentException ->
        if (message?.contains("members", ignoreCase = true) == true) "This video is for the channel's paying members only."
        else "This video has to be bought on YouTube."
    is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ->
        message ?: "This video isn't available."
    else -> "Something went wrong (${javaClass.simpleName}). " + (message?.take(160) ?: "")
}
