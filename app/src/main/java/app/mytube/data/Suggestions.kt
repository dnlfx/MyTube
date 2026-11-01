package app.mytube.data

import kotlin.math.min
import kotlin.math.pow

/**
 * Builds a personal feed entirely on the phone from watch history.
 *
 * Every watched video carries the "Up next" list YouTube showed for it. Candidates from those lists
 * score higher when they came from recently watched videos, ranked near the top of their list,
 * or appeared under several watched videos. Channels you return to get a boost. Anything already
 * watched is dropped, and no channel takes more than a few slots, so the feed stays varied.
 */
object Suggestions {
    private const val RECENT = 30
    private const val RECENCY_DECAY = 0.88
    private const val PER_CHANNEL = 3

    fun build(history: List<HistoryEntry>, exclude: Set<String> = emptySet(), limit: Int = 60): List<Video> {
        if (history.isEmpty()) return emptyList()
        val watched = history.mapTo(HashSet()) { it.video.url }
        val channelVisits = history.groupingBy { it.video.channelName }.eachCount()

        val scores = HashMap<String, Double>()
        val videos = HashMap<String, Video>()
        history.take(RECENT).forEachIndexed { i, entry ->
            val recency = RECENCY_DECAY.pow(i)
            entry.related.forEachIndexed { rank, video ->
                if (video.url in watched || video.url in exclude) return@forEachIndexed
                val rankWeight = 1.0 / (1.0 + rank * 0.18)
                scores[video.url] = (scores[video.url] ?: 0.0) + recency * rankWeight
                videos.putIfAbsent(video.url, video)
            }
        }

        val ranked = scores.entries
            .map { (url, score) ->
                val video = videos.getValue(url)
                val visits = channelVisits[video.channelName] ?: 0
                video to score * (1.0 + 0.12 * min(visits, 6))
            }
            .sortedByDescending { it.second }

        val perChannel = HashMap<String, Int>()
        val result = ArrayList<Video>(limit)
        for ((video, _) in ranked) {
            val count = perChannel[video.channelName] ?: 0
            if (count >= PER_CHANNEL) continue
            perChannel[video.channelName] = count + 1
            result += video
            if (result.size == limit) break
        }
        return result
    }
}
