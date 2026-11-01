package app.mytube.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo

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

    fun remember(videos: List<Video>) = videos.forEach { previews[it.url] = it }
    fun preview(url: String): Video? = previews[url]

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(
            query,
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            "",
        )
        val info = SearchInfo.getInfo(service, handler)
        SearchPage(handler, info.relatedItems.toVideos().also(::remember), info.nextPage)
    }

    suspend fun more(page: SearchPage): SearchPage = withContext(Dispatchers.IO) {
        val result = SearchInfo.getMoreItems(service, page.handler, page.next)
        SearchPage(page.handler, result.items.toVideos().also(::remember), result.nextPage)
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /**
     * Loads a video's page. Retries a couple of times, because over a VPN YouTube often answers
     * the first request with a bot check or a timeout and then succeeds on the next one.
     */
    suspend fun stream(url: String): StreamInfo = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                return@withContext StreamInfo.getInfo(service, url)
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
