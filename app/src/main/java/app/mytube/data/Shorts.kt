package app.mytube.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Picks Shorts for you, entirely on the phone.
 *
 * Every channel gets a score. Shorts you liked, watched to the end or replayed raise their
 * channel's score; ones you swiped past right away lower it. Channels YouTube suggested next to
 * Shorts you enjoyed get a share of that score, which is how new channels are discovered. Channels
 * you follow or watch regular videos from get a head start. The best channels' Shorts shelves are
 * then mixed, weighted by score, skipping anything you've already seen, with no channel allowed to
 * crowd the feed. A few lower-ranked channels are always mixed in so the feed doesn't get stale.
 */
object Shorts {
    private const val TTL_MS = 30 * 60 * 1000L
    // Shelves older than this aren't shown even while a fresh copy loads.
    private const val STALE_MS = 3 * 24 * 60 * 60 * 1000L
    // How long to wait for a channel before building the feed without it. It still finishes
    // loading in the background, so it's ready next time.
    private const val WAIT_MS = 5_000L
    private const val PER_CHANNEL = 20
    private const val TOP_CHANNELS = 10
    private const val EXPLORE_CHANNELS = 3
    private const val RECENCY_DECAY = 0.97
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()
    private val loading = ConcurrentHashMap<String, Deferred<List<Video>>>()
    private val limit = Semaphore(6)
    // Loads run here, not in the caller, so leaving the tab doesn't throw away a half-done load.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var file: File? = null
    private var warmed = false

    /** Loads the shelves saved last time, so the Shorts tab can open without waiting on the network. */
    fun init(context: Context) {
        val f = File(context.cacheDir, "shorts_shelves.json").also { file = it }
        scope.launch {
            runCatching {
                val root = JSONObject(f.readText())
                val now = System.currentTimeMillis()
                root.keys().forEach { key ->
                    val entry = root.getJSONObject(key)
                    val at = entry.getLong("at")
                    if (now - at > STALE_MS) return@forEach
                    val items = entry.getJSONArray("videos")
                    val videos = List(items.length()) { items.getJSONObject(it).toVideo() }
                    cache.putIfAbsent(key, at to videos)
                }
            }
        }
    }

    /**
     * Starts loading the top channels' shelves in the background, so the Shorts tab is ready by
     * the time you open it.
     */
    fun warm() {
        if (warmed) return
        warmed = true
        scope.launch {
            delay(1_500)
            val scored = channelScores().values.filter { it.score > 0 }.sortedByDescending { it.score }
            scored.take(TOP_CHANNELS).forEach { c ->
                val key = Subscriptions.channelKey(c.ref.url)
                val fresh = cache[key]?.let { System.currentTimeMillis() - it.first < TTL_MS } == true
                if (!fresh) fetch(key, c.ref)
            }
        }
    }

    private class Candidate(val ref: ChannelRef, var score: Double)

    /** Scores every channel we know of. Higher means more likely to have Shorts you'll enjoy. */
    private fun channelScores(): Map<String, Candidate> {
        val scores = HashMap<String, Candidate>()
        fun add(url: String?, name: String, avatar: String?, points: Double) {
            if (url.isNullOrBlank()) return
            scores.getOrPut(Subscriptions.channelKey(url)) { Candidate(ChannelRef(url, name, avatar), 0.0) }.score += points
        }

        Subscriptions.channels.value.forEach { add(it.url, it.name, it.avatar, 2.0) }
        // Channels whose regular videos you enjoy are likely to have Shorts you'll enjoy too.
        History.entries.value.take(200).forEachIndexed { i, e ->
            add(e.video.channelUrl, e.video.channelName, e.video.channelAvatar, 0.4 * e.satisfaction * RECENCY_DECAY.pow(i / 4))
        }

        ShortsHistory.views.value.forEachIndexed { i, view ->
            val weight = view.engagement * RECENCY_DECAY.pow(i)
            add(view.video.channelUrl, view.video.channelName, view.video.channelAvatar, weight * 1.5)
            if (weight > 0) {
                view.related.forEachIndexed { rank, ref ->
                    add(ref.url, ref.name, ref.avatar, weight * 0.5 / (1 + rank * 0.25))
                }
            }
        }
        // "Don't recommend channel" removes a channel entirely.
        val feedback = Feedback.state.value
        scores.keys.removeAll { it in feedback.hiddenChannels }
        return scores
    }

    /**
     * Builds a batch of Shorts, best first. [exclude] holds Shorts already in the feed, so asking
     * again returns fresh ones. [force] reloads channels instead of using the 30 minute cache.
     */
    suspend fun recommend(exclude: Set<String> = emptySet(), force: Boolean = false, size: Int = 40): List<Video> = coroutineScope {
        val scored = withContext(Dispatchers.Default) { channelScores().values.filter { it.score > 0 }.sortedByDescending { it.score } }
        if (scored.isEmpty()) return@coroutineScope emptyList()

        // The best channels, plus a few picked at random from further down for discovery.
        val top = scored.take(TOP_CHANNELS)
        val rest = scored.drop(TOP_CHANNELS)
        val explore = rest.shuffled(Random(System.nanoTime())).take(EXPLORE_CHANNELS)
        val picked = top + explore

        val shelves = picked.map { c ->
            async { c to shelf(c.ref, force) }
        }.awaitAll()

        withContext(Dispatchers.Default) {
            val seen = ShortsHistory.views.value.mapTo(HashSet()) { it.video.url } + exclude
            val feedback = Feedback.state.value
            val maxScore = picked.maxOf { it.score }.coerceAtLeast(0.01)
            val random = Random(System.nanoTime())
            val ranked = shelves.flatMap { (c, videos) ->
                val channelWeight = 0.35 + c.score / maxScore
                videos.filter { it.url !in seen && !feedback.hides(it) }.mapIndexed { pos, v ->
                    val popularity = if (v.viewCount > 0) 1 + 0.08 * log10(v.viewCount.toDouble()) else 1.0
                    // A little randomness so two refreshes never look the same.
                    val jitter = 0.8 + 0.4 * random.nextDouble()
                    v to channelWeight * popularity * jitter / (1 + pos * 0.12)
                }
            }.sortedByDescending { it.second }

            // Each further pick from the same channel counts for less, so the feed stays varied.
            val taken = HashMap<String, Int>()
            val result = ArrayList<Video>()
            val pool = ranked.toMutableList()
            while (result.size < size && pool.isNotEmpty()) {
                var bestIndex = 0
                var best = -1.0
                for (i in 0 until min(pool.size, 60)) {
                    val (v, s) = pool[i]
                    val n = taken[v.channelUrl.orEmpty()] ?: 0
                    val adjusted = s * 0.55.pow(n)
                    if (adjusted > best) { best = adjusted; bestIndex = i }
                }
                val (v, _) = pool.removeAt(bestIndex)
                taken[v.channelUrl.orEmpty()] = (taken[v.channelUrl.orEmpty()] ?: 0) + 1
                result += v
            }
            result
        }
    }

    /**
     * A channel's Shorts. A shelf from the last 30 minutes is used as is. An older one is used
     * right away while a fresh copy loads for next time. With nothing saved, waits up to
     * [WAIT_MS] for the channel, then carries on without it.
     */
    private suspend fun shelf(ref: ChannelRef, force: Boolean): List<Video> {
        val key = Subscriptions.channelKey(ref.url)
        val saved = cache[key]
        val age = saved?.let { System.currentTimeMillis() - it.first } ?: Long.MAX_VALUE
        if (!force && saved != null && age < TTL_MS) return saved.second
        val load = fetch(key, ref)
        if (!force && saved != null && age < STALE_MS) return saved.second
        return withTimeoutOrNull(if (force) WAIT_MS * 2 else WAIT_MS) {
            runCatching { load.await() }.getOrNull()
        } ?: saved?.second.orEmpty()
    }

    /** Loads a channel's shelf in the background, sharing a load that's already under way. */
    private fun fetch(key: String, ref: ChannelRef): Deferred<List<Video>> {
        loading[key]?.let { return it }
        val load = scope.async(start = CoroutineStart.LAZY) {
            try {
                limit.withPermit { load(ref) }.also {
                    cache[key] = System.currentTimeMillis() to it
                    save()
                }
            } finally {
                loading.remove(key)
            }
        }
        loading.putIfAbsent(key, load)?.let { other ->
            load.cancel()
            return other
        }
        load.start()
        return load
    }

    private suspend fun save() {
        val f = file ?: return
        writeLock.withLock {
            runCatching {
                val root = JSONObject()
                cache.forEach { (key, entry) ->
                    root.put(key, JSONObject().put("at", entry.first).put("videos", JSONArray(entry.second.map { it.toJson() })))
                }
                val tmp = File(f.path + ".tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(f)
            }
        }
    }

    private suspend fun load(ref: ChannelRef): List<Video> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val id = service.channelLHFactory.fromUrl(ref.url).id
        val handler = service.channelTabLHFactory.fromQuery(id, listOf(ChannelTabs.SHORTS), "")
        ChannelTabInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filterNot { it.isLocked() }
            .take(PER_CHANNEL)
            .map { item ->
                // The Shorts shelf leaves out who posted it; we know from the channel we asked.
                item.toVideo().let { v ->
                    v.copy(
                        channelUrl = v.channelUrl ?: ref.url,
                        channelName = v.channelName.ifBlank { ref.name },
                        channelAvatar = v.channelAvatar ?: ref.avatar,
                    )
                }
            }
    }

    /** Channels shown next to a video, as leads for finding more Shorts like it. */
    fun relatedChannels(info: org.schabi.newpipe.extractor.stream.StreamInfo): List<ChannelRef> =
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val url = item.uploaderUrl ?: return@mapNotNull null
                ChannelRef(url, item.uploaderName.orEmpty(), item.uploaderAvatars.fitting(AVATAR_WIDTH))
            }
            .distinctBy { Subscriptions.channelKey(it.url) }
            .filter { Subscriptions.channelKey(it.url) != info.uploaderUrl?.let(Subscriptions::channelKey) }
            .take(12)
}
