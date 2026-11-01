package app.mytube.player

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Always picks the highest resolution and frame rate the phone can decode.
 * At equal quality it prefers the most efficient codec with a hardware decoder (AV1, then VP9, then H.264).
 */
object StreamPicker {

    private val usable = setOf(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.DASH)

    // Software decoding above 1080p drops frames on most phones.
    private const val SOFTWARE_MAX_HEIGHT = 1080

    data class Choice(val stream: VideoStream, val decoder: CodecSupport.Decoder, val mime: String?)

    fun bestVideo(streams: List<VideoStream>): Choice? =
        streams.asSequence()
            .filter { it.deliveryMethod in usable && it.itagItem != null }
            .mapNotNull { stream ->
                val mime = CodecSupport.mimeFor(stream.codec) ?: guessMime(stream.format)
                val decoder = mime?.let {
                    CodecSupport.decoderFor(it, stream.width, stream.height, stream.fps)
                } ?: CodecSupport.Decoder.SOFTWARE
                val playable = decoder == CodecSupport.Decoder.HARDWARE ||
                    (decoder == CodecSupport.Decoder.SOFTWARE && stream.height <= SOFTWARE_MAX_HEIGHT)
                if (playable) Choice(stream, decoder, mime) else null
            }
            .sortedWith(
                compareByDescending<Choice> { it.stream.height }
                    .thenByDescending { it.stream.fps }
                    .thenByDescending { if (it.decoder == CodecSupport.Decoder.HARDWARE) 1 else 0 }
                    .thenByDescending { codecRank(it.mime) }
                    .thenByDescending { it.stream.bitrate },
            )
            .firstOrNull()

    fun bestMuxed(streams: List<VideoStream>): VideoStream? =
        streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }

    fun bestAudio(streams: List<AudioStream>): AudioStream? {
        val candidates = streams.filter { it.deliveryMethod in usable && it.itagItem != null }
        // Prefer the original language track over dubbed or described audio.
        val original = candidates.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { candidates }
        return original.maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
    }

    private fun guessMime(format: MediaFormat?): String? = when (format) {
        MediaFormat.WEBM -> "video/x-vnd.on2.vp9"
        MediaFormat.MPEG_4 -> "video/avc"
        else -> null
    }

    private fun codecRank(mime: String?): Int = when (mime) {
        "video/av01" -> 3
        "video/x-vnd.on2.vp9" -> 2
        "video/avc" -> 1
        else -> 0
    }

    fun label(choice: Choice?): String? {
        val s = choice?.stream ?: return null
        val codec = when (choice.mime) {
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/avc" -> "H.264"
            "video/hevc" -> "HEVC"
            else -> null
        }
        val res = s.resolution?.takeIf { it.isNotBlank() } ?: "${s.height}p"
        return listOfNotNull(res, codec).joinToString(" · ")
    }
}
