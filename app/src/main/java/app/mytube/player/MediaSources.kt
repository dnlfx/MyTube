package app.mytube.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.ByteArrayInputStream
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/** [hdr] is true when the chosen stream is HDR, which needs a SurfaceView to display properly. */
class Playback(val source: MediaSource, val qualityLabel: String?, val hdr: Boolean = false)

/** Turns extracted stream info into something the player can play, at the best quality available. */
object MediaSources {

    /** [allowHdr] false picks SDR even on HDR screens, for places that draw video on a TextureView. */
    fun build(info: StreamInfo, dataSource: DataSource.Factory, allowHdr: Boolean = true): Playback {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) {
            info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return Playback(hls(it, dataSource), "Live") }
            info.dashMpdUrl?.takeIf { it.isNotBlank() }?.let {
                return Playback(DashMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(it)), "Live")
            }
        }

        val video = StreamPicker.bestVideo(info.videoOnlyStreams, allowHdr)
        val audio = StreamPicker.bestAudio(info.audioStreams)
        if (video != null && audio != null) {
            val merged = MergingMediaSource(
                true,
                youtube(video.stream, info.duration, dataSource),
                youtube(audio, info.duration, dataSource),
            )
            return Playback(merged, StreamPicker.label(video), hdr = video.hdr)
        }

        StreamPicker.bestMuxed(info.videoStreams)?.let { muxed ->
            val source = ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(muxed.content))
            return Playback(source, "${muxed.height}p")
        }

        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return Playback(hls(it, dataSource), null) }
        error("No playable stream found for this video")
    }

    private fun hls(url: String, dataSource: DataSource.Factory): MediaSource =
        HlsMediaSource.Factory(dataSource).setAllowChunklessPreparation(true).createMediaSource(MediaItem.fromUri(url))

    private fun youtube(stream: Stream, durationSeconds: Long, dataSource: DataSource.Factory): MediaSource {
        val itag = requireNotNull(stream.itagItem)
        val manifest = when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> runCatching {
                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag, durationSeconds)
            }.getOrNull()
            DeliveryMethod.DASH ->
                YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag, durationSeconds)
            else -> null
        } ?: return ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(stream.content))

        val uri = Uri.parse(stream.content)
        val parsed = DashManifestParser().parse(uri, ByteArrayInputStream(manifest.toByteArray()))
        return DashMediaSource.Factory(dataSource).createMediaSource(parsed, MediaItem.fromUri(uri))
    }
}
