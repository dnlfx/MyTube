package app.mytube.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.feed.FeedInfo

/** The newest uploads from every followed channel, merged into one list. */
object Feed {
    data class State(
        val videos: List<Video> = emptyList(),
        val loading: Boolean = false,
        /** Channels whose uploads couldn't be loaded on the last refresh. */
        val failed: Int = 0,
        val loadedAt: Long = 0,
    )

    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_VIDEOS = 300
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()
    private val limit = Semaphore(4)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshLock = Mutex()

    /**
     * Reloads channels whose uploads are older than 15 minutes, or all of them when [force]d.
     * Runs on its own scope, so leaving the home screen doesn't cut a refresh short.
     */
    fun refresh(channels: List<Channel>, force: Boolean = false) {
        scope.launch {
            refreshLock.withLock {
                if (channels.isEmpty()) {
                    _state.value = State()
                    return@withLock
                }
                _state.value = _state.value.copy(loading = true)
                try {
                    load(channels, force)
                } finally {
                    _state.value = _state.value.copy(loading = false)
                }
            }
        }
    }

    private suspend fun load(channels: List<Channel>, force: Boolean) {
        val now = System.currentTimeMillis()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val lists = coroutineScope {
            channels.map { channel ->
                async {
                    val key = Subscriptions.channelKey(channel.url)
                    val hit = cache[key]?.takeIf { !force && now - it.first < TTL_MS }
                    hit?.second ?: limit.withPermit {
                        runCatching { uploads(channel) }
                            .onSuccess { cache[key] = System.currentTimeMillis() to it }
                            .getOrElse { failed.incrementAndGet(); cache[key]?.second.orEmpty() }
                    }
                }
            }.awaitAll()
        }
        val merged = withContext(Dispatchers.Default) {
            lists.flatten().distinctBy { it.url }.sortedByDescending { it.uploadedAt }.take(MAX_VIDEOS)
        }
        YouTube.remember(merged)
        _state.value = State(merged, loading = true, failed = failed.get(), loadedAt = System.currentTimeMillis())
    }

    /** Forgets a channel's uploads, e.g. right after unfollowing it. */
    fun drop(channelUrl: String) {
        val key = Subscriptions.channelKey(channelUrl)
        cache.remove(key)
        val s = _state.value
        _state.value = s.copy(videos = s.videos.filterNot { it.channelUrl?.let(Subscriptions::channelKey) == key })
    }

    /**
     * Reads the channel's RSS feed: one small request with the latest 15 uploads, dates and
     * views. If the feed isn't available, falls back to the channel's videos tab.
     */
    private suspend fun uploads(channel: Channel): List<Video> = withContext(Dispatchers.IO) {
        val rss = runCatching {
            FeedInfo.getInfo(ServiceList.YouTube, channel.url).relatedItems
                .filterNot { it.isShort() }
                .map { item ->
                    val v = item.toVideo()
                    v.copy(
                        channelAvatar = channel.avatar,
                        // The feed lists a blurry 320px thumbnail; the 480px one always exists too.
                        thumbnail = v.thumbnail?.replace("mqdefault", "hqdefault"),
                        uploaded = formatAgo(v.uploadedAt),
                        durationSeconds = 0,
                    )
                }
        }.getOrNull()
        rss ?: YouTube.channel(channel.url).videos.map { it.copy(channelAvatar = it.channelAvatar ?: channel.avatar) }
    }
}
