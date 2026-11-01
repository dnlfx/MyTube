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
    /** True while the user is typing, so suggestions show instead of results. */
    var editing by mutableStateOf(true)
        private set

    private var page: SearchPage? = null
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
        suggestJob?.cancel()
        searchJob?.cancel()
        results = Results.Loading
        searchJob = viewModelScope.launch {
            results = runCatching { YouTube.search(q) }.fold(
                onSuccess = { page = it; Results.Loaded(it.videos, loadingMore = false) },
                onFailure = { Results.Failed(it.friendlyMessage()) },
            )
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
            val seen = loaded.videos.mapTo(HashSet()) { it.url }
            results = Results.Loaded(loaded.videos + next.videos.filter { it.url !in seen }, loadingMore = false)
        }
    }
}

fun Throwable.friendlyMessage(): String = when (this) {
    is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
        "Couldn't reach YouTube. Check your connection and try again."
    is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ->
        "YouTube is asking for a captcha. Wait a few minutes and try again."
    is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException ->
        "This video is age-restricted and can't be played without signing in."
    is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ->
        message ?: "This video isn't available."
    else -> "Something went wrong. " + (message?.take(160) ?: "")
}
