package app.mytube.data

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    private const val PER_CHANNEL = 20
    private const val TOP_CHANNELS = 10
    private const val EXPLORE_CHANNELS = 3
    private const val RECENCY_DECAY = 0.97
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()
    private val limit = Semaphore(4)

    private class Candidate(val ref: ChannelRef, var score: Double)

    /** Scores every channel we know of. Higher means more likely to have Shorts you'll enjoy. */
    private fun channelScores(): Map<String, Candidate> {
        val scores = HashMap<String, Candidate>()
        fun add(url: String?, name: String, avatar: String?, points: Double) {
            if (url.isNullOrBlank()) return
            scores.getOrPut(Subscriptions.channelKey(url)) { Candidate(ChannelRef(url, name, avatar), 0.0) }.score += points
        }

        Subscriptions.channels.value.forEach { add(it.url, it.name, it.avatar, 2.0) }
        History.entries.value.take(200).forEach { add(it.video.channelUrl, it.video.channelName, it.video.channelAvatar, 0.25) }

        ShortsHistory.views.value.forEachIndexed { i, view ->
            val weight = view.engagement * RECENCY_DECAY.pow(i)
            add(view.video.channelUrl, view.video.channelName, view.video.channelAvatar, weight * 1.5)
            if (weight > 0) {
                view.related.forEachIndexed { rank, ref ->
                    add(ref.url, ref.name, ref.avatar, weight * 0.5 / (1 + rank * 0.25))
                }
            }
        }
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
            val maxScore = picked.maxOf { it.score }.coerceAtLeast(0.01)
            val random = Random(System.nanoTime())
            val ranked = shelves.flatMap { (c, videos) ->
                val channelWeight = 0.35 + c.score / maxScore
                videos.filter { it.url !in seen }.mapIndexed { pos, v ->
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

    private suspend fun shelf(ref: ChannelRef, force: Boolean): List<Video> {
        val key = Subscriptions.channelKey(ref.url)
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { !force && now - it.first < TTL_MS }?.let { return it.second }
        return limit.withPermit {
            runCatching { load(ref) }
                .onSuccess { cache[key] = System.currentTimeMillis() to it }
                .getOrElse { cache[key]?.second.orEmpty() }
        }
    }

    private suspend fun load(ref: ChannelRef): List<Video> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val id = service.channelLHFactory.fromUrl(ref.url).id
        val handler = service.channelTabLHFactory.fromQuery(id, listOf(ChannelTabs.SHORTS), "")
        ChannelTabInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
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
