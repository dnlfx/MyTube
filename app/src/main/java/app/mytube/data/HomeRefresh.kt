package app.mytube.data

import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What pulling down on Home changes. Kept for as long as the app runs, so switching tabs and
 * coming back shows the same feed.
 *
 * A refresh does two things, like YouTube's own home feed:
 * - Videos you already scrolled past sink, so picks you haven't seen take the top.
 * - A few videos you watched get their "up next" list loaded again. YouTube suggests different
 *   things each time, so this brings in videos that weren't in the feed before.
 */
object HomeRefresh {
    @Immutable
    data class State(
        /** How many refreshes so far. Also reshuffles near-ties in the ranking. */
        val round: Int = 0,
        /** How many refreshes each video has sat through on Home without being opened. */
        val shown: Map<String, Int> = emptyMap(),
        /** "Up next" lists loaded again on refresh, by the watched video they belong to. */
        val fresh: Map<String, List<Video>> = emptyMap(),
    )

    /** Watched videos whose "up next" list is loaded again on each refresh. */
    private const val SEEDS_PER_REFRESH = 3
    /** Longest a refresh waits for those lists; anything slower is skipped this time. */
    private const val WAIT_MS = 6_000L
    /** Short enough that a quick refresh still shows the spinner. */
    private const val MIN_SPIN_MS = 450L
    private const val SEED_POOL = 40
    private const val MAX_FRESH = 40
    private const val RECENCY_DECAY = 0.93

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Loads keep going if you leave Home, and a slow one never holds up the spinner.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val reloaded = ConcurrentHashMap.newKeySet<String>()

    /** Notes videos that appeared on screen, so the next refresh can move past them. */
    fun markSeen(urls: Collection<String>) {
        seen += urls
    }

    /** Sinks what you've already seen and pulls in new candidates. Returns once the feed can be rebuilt. */
    suspend fun refresh(history: List<HistoryEntry>) {
        val started = System.currentTimeMillis()
        val loaded = ConcurrentHashMap<String, List<Video>>()
        val jobs = pickSeeds(history).map { entry ->
            scope.launch {
                runCatching { YouTube.stream(entry.video.url).relatedItems.toVideos() }
                    .onSuccess { related ->
                        if (related.isNotEmpty()) {
                            loaded[entry.video.url] = related
                            reloaded += entry.video.url
                        }
                    }
            }
        }
        withTimeoutOrNull(WAIT_MS) { jobs.joinAll() }
        val fresh = HashMap(loaded)
        YouTube.remember(fresh.values.flatten())

        val seenNow = seen.toSet()
        seen.removeAll(seenNow)
        val current = _state.value
        val shown = HashMap(current.shown)
        seenNow.forEach { shown[it] = (shown[it] ?: 0) + 1 }
        _state.value = State(
            round = current.round + 1,
            shown = shown,
            fresh = current.fresh + fresh.mapValues { (url, new) ->
                (new + current.fresh[url].orEmpty()).distinctBy { it.url }.take(MAX_FRESH)
            },
        )

        val left = MIN_SPIN_MS - (System.currentTimeMillis() - started)
        if (left > 0) delay(left)
    }

    /**
     * The watched videos most worth asking YouTube about again: recent ones you enjoyed, skipping
     * ones already reloaded so each refresh brings in a different set.
     */
    private fun pickSeeds(history: List<HistoryEntry>): List<HistoryEntry> {
        val pool = history.take(SEED_POOL).filterNot { it.video.isLive || it.video.membersOnly }
        if (pool.isEmpty()) return emptyList()
        // Once every seed has had a turn, start over: by then YouTube has new picks for them.
        if (pool.all { it.video.url in reloaded }) reloaded.removeAll(pool.map { it.video.url }.toSet())
        return pool.withIndex()
            .filterNot { it.value.video.url in reloaded }
            .sortedByDescending { (i, entry) -> RECENCY_DECAY.pow(i) * entry.satisfaction }
            .take(SEEDS_PER_REFRESH)
            .map { it.value }
    }
}
