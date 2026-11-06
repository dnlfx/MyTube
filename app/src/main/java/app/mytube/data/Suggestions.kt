package app.mytube.data

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Builds a personal feed entirely on the phone, following the same two steps YouTube describes
 * for its own recommendations: gather candidates, then rank them by how likely you are to enjoy
 * watching them.
 *
 * 1. Candidates come from the "watch next" graph. Every video you watched carries the list YouTube
 *    showed next to it. Each candidate collects points from every watched video that led to it,
 *    so a video that keeps coming up next to things you enjoyed rises.
 * 2. Those points are weighted by satisfaction, not clicks: a video you finished counts far more
 *    than one you opened and left, and recent viewing counts more than old.
 * 3. Ranking adds channel affinity (how much you enjoy that channel's videos on average, and
 *    whether you follow it or saved its videos), freshness (newer uploads get a lift, very old
 *    ones a small dip), and a light popularity signal.
 * 4. Anything already watched, marked "Not interested", or from a channel you hid is removed.
 * 5. Variety: each further pick from the same channel is worth less, and a few slots go to
 *    lower-ranked videos so the feed keeps discovering instead of narrowing.
 * 6. Refreshing Home sinks videos it already showed you and adds newly loaded "up next" lists
 *    (see [HomeRefresh]), so each refresh leads with picks you haven't seen.
 */
object Suggestions {
    private const val SEEDS = 40
    private const val RECENCY_DECAY = 0.93
    private const val SAME_CHANNEL_DECAY = 0.5
    private const val EXPLORE_EVERY = 6
    /** Each refresh a video sat through on Home without being opened leaves it worth this much. */
    private const val SHOWN_DECAY = 0.3
    /** A small lift for candidates that only a refresh brought in. */
    private const val NEW_LIFT = 1.2

    /**
     * @param followed channel keys (see [Subscriptions.channelKey]) of followed channels.
     * @param seed when not 0, reshuffles near-ties so a refresh brings different picks to the top.
     * @param shown how many refreshes each video has already been shown through (see [HomeRefresh]).
     * @param fresh newly loaded "up next" lists, by the watched video they belong to.
     */
    fun build(
        history: List<HistoryEntry>,
        exclude: Set<String> = emptySet(),
        limit: Int = 60,
        followed: Set<String> = emptySet(),
        seed: Int = 0,
        feedback: Feedback.State = Feedback.state.value,
        saved: List<Video> = WatchLater.videos.value,
        now: Long = System.currentTimeMillis(),
        shown: Map<String, Int> = emptyMap(),
        fresh: Map<String, List<Video>> = emptyMap(),
    ): List<Video> {
        if (history.isEmpty()) return emptyList()
        val watched = history.mapTo(HashSet()) { it.video.url }

        // How much you enjoy each channel, averaged over what you watched from it.
        val channelSatisfaction = HashMap<String, Pair<Double, Int>>()
        history.forEach { e ->
            val key = e.video.channelUrl?.let(Subscriptions::channelKey) ?: return@forEach
            val (sum, n) = channelSatisfaction[key] ?: (0.0 to 0)
            channelSatisfaction[key] = (sum + e.satisfaction) to (n + 1)
        }
        val savedChannels = saved.mapNotNullTo(HashSet()) { it.channelUrl?.let(Subscriptions::channelKey) }

        fun affinity(video: Video): Double {
            val key = video.channelUrl?.let(Subscriptions::channelKey) ?: return 1.0
            var a = 1.0
            channelSatisfaction[key]?.let { (sum, n) ->
                val avg = sum / n
                // More videos watched from a channel makes its average more trustworthy.
                a *= 0.75 + avg * (0.5 + 0.12 * min(n, 6))
            }
            if (key in followed) a *= 1.35
            if (key in savedChannels) a *= 1.1
            return a
        }

        // Step 1 and 2: candidates from the watch-next graph, weighted by satisfaction and recency.
        val points = HashMap<String, Double>()
        val sources = HashMap<String, Int>()
        val videos = HashMap<String, Video>()
        val seeds = history.take(SEEDS)
        // Candidates that were already there before any refresh.
        val known = if (fresh.isEmpty()) emptySet() else seeds.flatMapTo(HashSet()) { e -> e.related.map { it.url } }
        seeds.forEachIndexed { i, entry ->
            val weight = RECENCY_DECAY.pow(i) * entry.satisfaction
            // Newly loaded picks go first, so they count as YouTube's top suggestions for this video.
            val related = fresh[entry.video.url]?.let { (it + entry.related).distinctBy { v -> v.url } } ?: entry.related
            related.forEachIndexed { rank, video ->
                if (video.url in watched || video.url in exclude || feedback.hides(video) || video.membersOnly) return@forEachIndexed
                points[video.url] = (points[video.url] ?: 0.0) + weight / (1.0 + rank * 0.15)
                sources[video.url] = (sources[video.url] ?: 0) + 1
                videos.putIfAbsent(video.url, video)
            }
        }
        if (points.isEmpty()) return emptyList()

        // Step 3: rank.
        val random = if (seed == 0) null else Random(seed)
        val ranked = points.entries.map { (url, p) ->
            val video = videos.getValue(url)
            // Showing up next to several things you watched is a strong sign.
            val coWatch = 1.0 + 0.25 * ln(sources.getValue(url).toDouble())
            val seenBefore = SHOWN_DECAY.pow(shown[url] ?: 0)
            val novelty = if (fresh.isNotEmpty() && url !in known) NEW_LIFT else 1.0
            val score = p * coWatch * affinity(video) * freshness(video, now) * popularity(video) *
                seenBefore * novelty * (random?.let { 0.8 + 0.4 * it.nextDouble() } ?: 1.0)
            video to score
        }.sortedByDescending { it.second }

        // Step 5: variety and exploration.
        val pool = ranked.toMutableList()
        val perChannel = HashMap<String, Int>()
        val result = ArrayList<Video>(limit)
        val explorer = Random(seed * 7919 + 17)
        while (result.size < limit && pool.isNotEmpty()) {
            val exploring = (result.size + 1) % EXPLORE_EVERY == 0 && pool.size > 20
            val index = if (exploring) {
                // Something from further down the list, so the feed doesn't narrow over time.
                10 + explorer.nextInt(min(pool.size, 60) - 10)
            } else {
                var best = 0
                var bestScore = -1.0
                for (i in 0 until min(pool.size, 40)) {
                    val (v, s) = pool[i]
                    val adjusted = s * SAME_CHANNEL_DECAY.pow(perChannel[v.channelName] ?: 0)
                    if (adjusted > bestScore) { bestScore = adjusted; best = i }
                }
                best
            }
            val picked = pool.removeAt(index)
            val video = picked.first
            // Never two from the same channel in a row.
            if (result.lastOrNull()?.channelName == video.channelName && pool.any { it.first.channelName != video.channelName }) {
                val alt = pool.indexOfFirst { it.first.channelName != video.channelName }
                val (other, _) = pool.removeAt(alt)
                pool.add(0, picked)
                result += other
                perChannel[other.channelName] = (perChannel[other.channelName] ?: 0) + 1
                continue
            }
            result += video
            perChannel[video.channelName] = (perChannel[video.channelName] ?: 0) + 1
        }
        return result
    }

    /** Newer uploads get a lift and very old ones a small dip, like YouTube's freshness signal. */
    private fun freshness(video: Video, now: Long): Double {
        if (video.isLive) return 1.15
        val at = video.uploadedAt.takeIf { it > 0 } ?: return 1.0
        val days = (now - at) / 86_400_000.0
        return when {
            days < 2 -> 1.3
            days < 7 -> 1.2
            days < 30 -> 1.08
            days < 365 -> 1.0
            days < 3 * 365 -> 0.92
            else -> 0.85
        }
    }

    /** A gentle nudge toward videos many people chose to watch. */
    private fun popularity(video: Video): Double =
        if (video.viewCount > 0) 0.85 + 0.05 * log10(video.viewCount.toDouble()).coerceAtMost(9.0) else 1.0
}
