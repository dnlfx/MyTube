package app.mytube.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo

class ChannelPage(
    val channel: Channel,
    val banner: String?,
    val description: String,
    val videos: List<Video>,
    internal val tab: ListLinkHandler?,
    internal val next: Page?,
) {
    val hasMore: Boolean get() = tab != null && Page.isValid(next)
}

class CommentsPage(
    val comments: List<Comment>,
    val disabled: Boolean,
    internal val info: CommentsInfo?,
    internal val next: Page?,
) {
    val hasMore: Boolean get() = info != null && Page.isValid(next)
}

class SearchPage(
    val handler: SearchQueryHandler,
    val videos: List<Video>,
    val next: Page?,
) {
    val hasMore: Boolean get() = Page.isValid(next)
}

/** All YouTube access goes through here. No account, no API key, no cookies. */
object YouTube {
    private val service get() = ServiceList.YouTube

    /** Videos seen in lists, so the watch page can show title and thumbnail instantly. */
    private val previews = ConcurrentHashMap<String, Video>()

    fun remember(videos: List<Video>) = videos.forEach { video ->
        previews[video.url] = video
        if (video.viewCount >= 0) viewCounts[video.url] = video.viewCount
    }
    fun preview(url: String): Video? = previews[url]

    // View counts we've seen anywhere, so a result YouTube sends without one can show it right away.
    private val viewCounts = ConcurrentHashMap<String, Long>()

    /** The view count last seen for [url], or null if we've never seen one. */
    fun knownViews(url: String): Long? = viewCounts[url]

    /** Fills in view counts YouTube left out, from ones we've already seen. */
    fun withKnownViews(videos: List<Video>): List<Video> =
        videos.map { v -> if (v.viewCount < 0) viewCounts[v.url]?.let { v.copy(viewCount = it) } ?: v else v }

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            "",
        )
        val info = SearchInfo.getInfo(service, handler)
        SearchPage(handler, info.relatedItems.toVideos(keepLocked = true).also(::remember), info.nextPage)
    }

    suspend fun more(page: SearchPage): SearchPage = withContext(Dispatchers.IO) {
        val result = SearchInfo.getMoreItems(service, page.handler, page.next)
        SearchPage(page.handler, result.items.toVideos(keepLocked = true).also(::remember), result.nextPage)
    }

    /** The top channel matches for a query, for the row above search results. */
    suspend fun searchChannels(query: String): List<Channel> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.CHANNELS),
            "",
        )
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<ChannelInfoItem>()
            .map { it.toChannel() }
            .take(12)
    }

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(service, url)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val tabInfo = tab?.let { runCatching { ChannelTabInfo.getInfo(service, it) }.getOrNull() }
        ChannelPage(
            channel = Channel(info.url, info.name, info.avatars.fitting(AVATAR_WIDTH), info.subscriberCount, false),
            banner = info.banners.best(),
            description = info.description.orEmpty(),
            videos = tabInfo?.relatedItems?.toVideos(keepLocked = true).orEmpty().also(::remember),
            tab = tab,
            next = tabInfo?.nextPage,
        )
    }

    suspend fun moreChannelVideos(page: ChannelPage): ChannelPage = withContext(Dispatchers.IO) {
        val result = ChannelTabInfo.getMoreItems(service, page.tab!!, page.next!!)
        ChannelPage(
            page.channel, page.banner, page.description,
            result.items.toVideos(keepLocked = true).also(::remember), page.tab, result.nextPage,
        )
    }

    suspend fun comments(url: String): CommentsPage = withContext(Dispatchers.IO) {
        val info = CommentsInfo.getInfo(service, url)
        CommentsPage(
            comments = info.relatedItems.map { it.toComment() },
            disabled = info.isCommentsDisabled,
            info = info,
            next = info.nextPage,
        )
    }

    suspend fun moreComments(page: CommentsPage): CommentsPage = withContext(Dispatchers.IO) {
        val result = CommentsInfo.getMoreItems(service, page.info!!, page.next!!)
        CommentsPage(result.items.map { it.toComment() }, false, page.info, result.nextPage)
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /**
     * Loads a video's page. Retries a couple of times, because over a VPN YouTube often answers
     * the first request with a bot check or a timeout and then succeeds on the next one.
     */
    private class Cached(val info: StreamInfo, val at: Long)
    private val streamCache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > 60
    }
    // Video links stay valid for hours; 10 minutes keeps us well inside that.
    private const val STREAM_TTL_MS = 10 * 60 * 1000L

    private fun cached(url: String): StreamInfo? = synchronized(streamCache) {
        streamCache[url]?.takeIf { System.currentTimeMillis() - it.at < STREAM_TTL_MS }?.info
    }

    /** Drops a cached page, e.g. after its video links stopped working. */
    fun forget(url: String) {
        synchronized(streamCache) { streamCache.remove(url) }
    }

    /**
     * Fills in view counts YouTube left out of a list (it hides some in search results) by
     * loading each video's page. The page is cached, so opening that video afterwards is instant.
     */
    suspend fun viewCount(url: String): Long = stream(url).viewCount

    suspend fun stream(url: String): StreamInfo = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        val info = fetchStream(url)
        synchronized(streamCache) { streamCache[url] = Cached(info, System.currentTimeMillis()) }
        if (info.viewCount >= 0) viewCounts[url] = info.viewCount
        info
    }

    private suspend fun fetchStream(url: String): StreamInfo {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                return StreamInfo.getInfo(service, url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (!e.isRetryable()) break
                if (attempt < 3) delay(700L * attempt)
            }
        }
        throw last!!
    }

    private fun Throwable.isRetryable(): Boolean =
        this is java.io.IOException ||
            this is ReCaptchaException ||
            this is SignInConfirmNotBotException ||
            cause?.let { it is java.io.IOException } == true
}
