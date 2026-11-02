package app.mytube.data

import androidx.compose.runtime.Immutable
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@Immutable
data class Video(
    val url: String,
    val title: String,
    val channelName: String,
    val channelUrl: String?,
    val channelAvatar: String?,
    val thumbnail: String?,
    val durationSeconds: Long,
    val viewCount: Long,
    val uploaded: String?,
    val isLive: Boolean,
    /** When it was uploaded, in epoch milliseconds, or 0 if unknown. */
    val uploadedAt: Long = 0,
    /** Only paying channel members can watch it, so it won't play here. */
    val membersOnly: Boolean = false,
)

@Immutable
data class VideoDetails(
    val video: Video,
    val description: String,
    val likeCount: Long,
    val subscriberCount: Long,
    val related: List<Video>,
)

/** Picks the sharpest image from a list YouTube returns in mixed sizes. */
fun List<Image>.best(): String? =
    maxByOrNull { image ->
        val h = image.height.takeIf { it > 0 } ?: 0
        val w = image.width.takeIf { it > 0 } ?: 0
        if (h == 0 && w == 0) 1 else w.toLong() * h.toLong()
    }?.url

/**
 * Picks the smallest image that is still at least [minWidth] pixels wide, so lists download
 * sharp images without pulling full-size ones. Falls back to the largest when none is big enough.
 */
fun List<Image>.fitting(minWidth: Int): String? {
    val sized = filter { it.width > 0 }
    if (sized.isEmpty()) return best()
    return (sized.filter { it.width >= minWidth }.minByOrNull { it.width } ?: sized.maxBy { it.width }).url
}

/** Wide enough for a full-width card on a phone. */
const val THUMB_WIDTH = 640
/** Wide enough for the biggest avatar we draw. */
const val AVATAR_WIDTH = 176

private val ytThumb = Regex("""^https?://i\d?\.ytimg\.com/(vi|vi_webp)/([A-Za-z0-9_-]{11})/([A-Za-z0-9_]+)\.(jpg|webp)""")

/**
 * A sharper version of a YouTube thumbnail. Lists hand out thumbnails shrunk to 336 pixels or
 * less, which look soft on a phone screen. YouTube keeps larger copies at fixed addresses: 1280
 * pixels wide for [wide] cards, 480 for small rows, and the full-size original for Shorts. Not
 * every video has the largest copy, so callers fall back to the original link if this one fails.
 * Returns null when [url] isn't a YouTube thumbnail.
 */
fun sharpThumbnail(url: String?, wide: Boolean = true): String? {
    val match = ytThumb.find(url ?: return null) ?: return null
    val (_, id, name) = match.destructured
    val file = when {
        // Shorts use vertical "oar" images; without the size parameters YouTube sends the original.
        name.startsWith("oar") || name.startsWith("frame") -> "$name.jpg"
        wide -> "hq720.jpg"
        else -> "hqdefault.jpg"
    }
    val sharp = "https://i.ytimg.com/vi/$id/$file"
    return sharp.takeIf { it != url }
}

private fun org.schabi.newpipe.extractor.localization.DateWrapper?.epochMs(): Long =
    runCatching { this?.instant?.toEpochMilli() }.getOrNull() ?: 0L

private fun StreamType.isLive() =
    this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

fun StreamInfoItem.isShort(): Boolean =
    isShortFormContent || url.contains("/shorts/")

/** True for videos that need a channel membership or a purchase, which can't play without an account. */
fun StreamInfoItem.isLocked(): Boolean =
    contentAvailability == ContentAvailability.MEMBERSHIP || contentAvailability == ContentAvailability.PAID

fun StreamInfoItem.toVideo() = Video(
    url = url,
    title = name,
    channelName = uploaderName.orEmpty(),
    channelUrl = uploaderUrl,
    channelAvatar = uploaderAvatars.fitting(AVATAR_WIDTH),
    thumbnail = thumbnails.fitting(THUMB_WIDTH),
    durationSeconds = duration,
    viewCount = viewCount,
    uploaded = textualUploadDate,
    isLive = streamType.isLive(),
    uploadedAt = runCatching { uploadDate }.getOrNull().epochMs(),
    membersOnly = isLocked(),
)

/**
 * Keeps regular videos only: no Shorts, channels, or playlists. [keepLocked] keeps members-only
 * videos (labelled as such), for places like search where you might be looking for one; elsewhere
 * they're dropped, since they can't play.
 */
fun List<InfoItem>.toVideos(keepLocked: Boolean = false): List<Video> =
    filterIsInstance<StreamInfoItem>()
        .filterNot { it.isShort() || (!keepLocked && it.isLocked()) }
        .map { it.toVideo() }

fun StreamInfo.toDetails() = VideoDetails(
    video = Video(
        url = url,
        title = name,
        channelName = uploaderName.orEmpty(),
        channelUrl = uploaderUrl,
        channelAvatar = uploaderAvatars.fitting(AVATAR_WIDTH),
        thumbnail = thumbnails.fitting(THUMB_WIDTH),
        durationSeconds = duration,
        viewCount = viewCount,
        uploaded = textualUploadDate,
        isLive = streamType.isLive(),
        uploadedAt = runCatching { uploadDate }.getOrNull().epochMs(),
    ),
    description = description?.content.orEmpty(),
    likeCount = likeCount,
    subscriberCount = uploaderSubscriberCount,
    related = relatedItems.toVideos(),
)

@Immutable
data class Channel(
    val url: String,
    val name: String,
    val avatar: String?,
    val subscriberCount: Long,
    val verified: Boolean,
)

@Immutable
data class Comment(
    val id: String,
    val author: String,
    val authorUrl: String?,
    val avatar: String?,
    val text: String,
    val likes: Int,
    val date: String?,
    val replies: Int,
    val pinned: Boolean,
    val hearted: Boolean,
    val byCreator: Boolean,
    /** When it was posted, in epoch milliseconds, or 0 if unknown. */
    val postedAt: Long = 0,
)

fun org.schabi.newpipe.extractor.channel.ChannelInfoItem.toChannel() = Channel(
    url = url,
    name = name,
    avatar = thumbnails.fitting(AVATAR_WIDTH),
    subscriberCount = subscriberCount,
    verified = isVerified,
)

fun org.schabi.newpipe.extractor.comments.CommentsInfoItem.toComment() = Comment(
    id = commentId ?: url ?: "",
    author = uploaderName.orEmpty(),
    authorUrl = uploaderUrl,
    avatar = uploaderAvatars.fitting(AVATAR_WIDTH),
    text = commentText?.content.orEmpty(),
    likes = likeCount,
    date = textualUploadDate,
    replies = replyCount,
    pinned = isPinned,
    hearted = isHeartedByUploader,
    byCreator = isChannelOwner,
    postedAt = runCatching { uploadDate }.getOrNull().epochMs(),
)
