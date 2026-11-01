package app.mytube.data

import java.util.concurrent.ConcurrentHashMap
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
 * Shorts live only in their own tab. They come from the Shorts shelf of channels you follow and
 * channels you watch most, picked on the phone like the rest of the app.
 */
object Shorts {
    private const val TTL_MS = 30 * 60 * 1000L
    private const val MAX_CHANNELS = 14
    private const val PER_CHANNEL = 12
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()
    private val limit = Semaphore(4)

    /** Channels to draw Shorts from: everything you follow, then the channels you watch most. */
    fun sourceChannels(subscriptions: List<Channel>, history: List<HistoryEntry>): List<String> {
        val followed = subscriptions.map { it.url }
        val watched = history.mapNotNull { it.video.channelUrl }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }
        val seen = HashSet<String>()
        return (followed + watched)
            .filter { seen.add(Subscriptions.channelKey(it)) }
            .take(MAX_CHANNELS)
    }

    /**
     * Loads Shorts from [channels] and deals them out one channel at a time, so no single channel
     * fills the feed. [force] skips the 30 minute cache.
     */
    suspend fun feed(channels: List<String>, force: Boolean = false): List<Video> = coroutineScope {
        val now = System.currentTimeMillis()
        val lists = channels.map { url ->
            async {
                val key = Subscriptions.channelKey(url)
                cache[key]?.takeIf { !force && now - it.first < TTL_MS }?.second
                    ?: limit.withPermit {
                        runCatching { load(url) }
                            .onSuccess { cache[key] = System.currentTimeMillis() to it }
                            .getOrElse { cache[key]?.second.orEmpty() }
                    }
            }
        }.awaitAll().map { list -> if (force) list.shuffled() else list }
        val dealt = ArrayList<Video>()
        val seen = HashSet<String>()
        for (round in 0 until PER_CHANNEL) {
            lists.forEach { list -> list.getOrNull(round)?.let { if (seen.add(it.url)) dealt += it } }
        }
        dealt
    }

    private suspend fun load(channelUrl: String): List<Video> = withContext(Dispatchers.IO) {
        val service = ServiceList.YouTube
        val id = service.channelLHFactory.fromUrl(channelUrl).id
        val handler = service.channelTabLHFactory.fromQuery(id, listOf(ChannelTabs.SHORTS), "")
        ChannelTabInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .take(PER_CHANNEL)
            .map { item ->
                item.toVideo().let { v ->
                    // The Shorts shelf leaves out who posted it; we know from the channel we asked.
                    v.copy(
                        channelUrl = v.channelUrl ?: channelUrl,
                        channelName = v.channelName.ifBlank { channelName(channelUrl) },
                        channelAvatar = v.channelAvatar ?: channelAvatar(channelUrl),
                    )
                }
            }
    }

    private fun channelName(url: String): String =
        Subscriptions.channels.value.firstOrNull { Subscriptions.channelKey(it.url) == Subscriptions.channelKey(url) }?.name
            ?: History.entries.value.firstOrNull { it.video.channelUrl == url }?.video?.channelName
            ?: ""

    private fun channelAvatar(url: String): String? =
        Subscriptions.channels.value.firstOrNull { Subscriptions.channelKey(it.url) == Subscriptions.channelKey(url) }?.avatar
            ?: History.entries.value.firstOrNull { it.video.channelUrl == url }?.video?.channelAvatar
}
