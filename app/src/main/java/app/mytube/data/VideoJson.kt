package app.mytube.data

import org.json.JSONObject

internal fun Video.toJson() = JSONObject().apply {
    put("url", url)
    put("title", title)
    put("channelName", channelName)
    putOpt("channelUrl", channelUrl)
    putOpt("channelAvatar", channelAvatar)
    putOpt("thumbnail", thumbnail)
    put("duration", durationSeconds)
    put("views", viewCount)
    putOpt("uploaded", uploaded)
    put("live", isLive)
    if (uploadedAt > 0) put("uploadedAt", uploadedAt)
}

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.toVideo() = Video(
    url = getString("url"),
    title = optString("title"),
    channelName = optString("channelName"),
    channelUrl = str("channelUrl"),
    channelAvatar = str("channelAvatar"),
    thumbnail = str("thumbnail"),
    durationSeconds = optLong("duration"),
    viewCount = optLong("views", -1),
    uploaded = str("uploaded"),
    isLive = optBoolean("live"),
    uploadedAt = optLong("uploadedAt"),
)
