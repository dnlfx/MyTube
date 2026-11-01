package app.mytube.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Answers "can this phone decode this video smoothly?" using its real hardware decoders. */
object CodecSupport {

    enum class Decoder { HARDWARE, SOFTWARE, NONE }

    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    }

    private val cache = HashMap<String, Decoder>()

    fun mimeFor(codec: String?): String? {
        val c = codec?.lowercase() ?: return null
        return when {
            c.startsWith("av01") -> "video/av01"
            c.startsWith("vp9") || c.startsWith("vp09") -> "video/x-vnd.on2.vp9"
            c.startsWith("avc") -> "video/avc"
            c.startsWith("hev") || c.startsWith("hvc") -> "video/hevc"
            else -> null
        }
    }

    @Synchronized
    fun decoderFor(mime: String, width: Int, height: Int, fps: Int): Decoder =
        cache.getOrPut("$mime/$width/$height/$fps") { find(mime, width, height, fps) }

    private fun find(mime: String, width: Int, height: Int, fps: Int): Decoder {
        var best = Decoder.NONE
        for (info in decoders) {
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                ?: continue
            if (!supports(caps, width, height, fps) && !supports(caps, height, width, fps)) continue
            if (isHardware(info)) return Decoder.HARDWARE
            best = Decoder.SOFTWARE
        }
        return best
    }

    private fun supports(caps: MediaCodecInfo.VideoCapabilities, w: Int, h: Int, fps: Int): Boolean =
        runCatching {
            when {
                w <= 0 || h <= 0 -> true
                fps > 0 -> caps.areSizeAndRateSupported(w, h, fps.toDouble())
                else -> caps.isSizeSupported(w, h)
            }
        }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains("sw"))
    }
}
