package app.mytube.data

import androidx.compose.runtime.Immutable
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
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

private fun StreamType.isLive() =
    this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

fun StreamInfoItem.isShort(): Boolean =
    isShortFormContent || url.contains("/shorts/")

fun StreamInfoItem.toVideo() = Video(
    url = url,
    title = name,
    channelName = uploaderName.orEmpty(),
    channelUrl = uploaderUrl,
    channelAvatar = uploaderAvatars.best(),
    thumbnail = thumbnails.best(),
    durationSeconds = duration,
    viewCount = viewCount,
    uploaded = textualUploadDate,
    isLive = streamType.isLive(),
)

/** Keeps regular videos only: no Shorts, channels, or playlists. */
fun List<InfoItem>.toVideos(): List<Video> =
    filterIsInstance<StreamInfoItem>()
        .filterNot { it.isShort() }
        .map { it.toVideo() }

fun StreamInfo.toDetails() = VideoDetails(
    video = Video(
        url = url,
        title = name,
        channelName = uploaderName.orEmpty(),
        channelUrl = uploaderUrl,
        channelAvatar = uploaderAvatars.best(),
        thumbnail = thumbnails.best(),
        durationSeconds = duration,
        viewCount = viewCount,
        uploaded = textualUploadDate,
        isLive = streamType.isLive(),
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
)

fun org.schabi.newpipe.extractor.channel.ChannelInfoItem.toChannel() = Channel(
    url = url,
    name = name,
    avatar = thumbnails.best(),
    subscriberCount = subscriberCount,
    verified = isVerified,
)

fun org.schabi.newpipe.extractor.comments.CommentsInfoItem.toComment() = Comment(
    id = commentId ?: url ?: "",
    author = uploaderName.orEmpty(),
    authorUrl = uploaderUrl,
    avatar = uploaderAvatars.best(),
    text = commentText?.content.orEmpty(),
    likes = likeCount,
    date = textualUploadDate,
    replies = replyCount,
    pinned = isPinned,
    hearted = isHeartedByUploader,
    byCreator = isChannelOwner,
)
