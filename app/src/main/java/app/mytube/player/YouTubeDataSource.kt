package app.mytube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.mytube.data.Network
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Shapes requests for YouTube's video servers the way its own clients do:
 * byte ranges go in a `range` query parameter, each request gets a sequence number,
 * and requests are POSTs with a tiny fixed body. Without this, servers throttle or reject them.
 */
object YouTubeDataSource {

    private val requestNumber = AtomicLong(0)
    private val postBody = byteArrayOf(0x78, 0)

    fun factory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(Network.client)
            .setDefaultRequestProperties(mapOf("User-Agent" to Network.USER_AGENT))
        return ResolvingDataSource.Factory(upstream, ::resolve)
    }

    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.path?.startsWith("/videoplayback") != true) return spec

        val builder = uri.buildUpon()
        if (uri.getQueryParameter("rn") == null) {
            builder.appendQueryParameter("rn", requestNumber.getAndIncrement().toString())
        }
        if (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (spec.length != C.LENGTH_UNSET.toLong()) (spec.position + spec.length - 1).toString() else ""
            builder.appendQueryParameter("range", "${spec.position}-$end")
        }
        val url: Uri = builder.build()
        val urlText = url.toString()

        val headers = buildMap {
            put("User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(urlText)) YoutubeParsingHelper.getVisionOsUserAgent(null)
                else Network.USER_AGENT)
            if (YoutubeParsingHelper.isWebStreamingUrl(urlText)) {
                put("Origin", "https://www.youtube.com")
                put("Referer", "https://www.youtube.com/")
                put("Sec-Fetch-Dest", "empty")
                put("Sec-Fetch-Mode", "cors")
                put("Sec-Fetch-Site", "cross-site")
            }
        }

        // The range now lives in the URL, so the request itself starts at 0 and reads to the end.
        return spec.buildUpon()
            .setUri(url)
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(postBody)
            .setHttpRequestHeaders(spec.httpRequestHeaders + headers)
            .build()
    }
}
