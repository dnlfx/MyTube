package app.mytube.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mytube.data.Feedback
import app.mytube.data.Video
import app.mytube.data.WatchLater

/**
 * The ⋮ menu on a video: save it for later, and, where it was recommended, tell the app you're
 * not interested in it or its channel. Like on YouTube, that feedback shapes what you see next.
 */
@Composable
fun VideoMenu(video: Video, feedback: Boolean, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val saved by WatchLater.videos.collectAsState()
    val isSaved = WatchLater.contains(video.url, saved)
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (isSaved) "Remove from Watch later" else "Save to Watch later") },
                leadingIcon = { Icon(if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null) },
                onClick = { WatchLater.toggle(video); open = false },
            )
            if (feedback) {
                DropdownMenuItem(
                    text = { Text("Not interested") },
                    leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                    onClick = { Feedback.notInterested(video); open = false },
                )
                val channelUrl = video.channelUrl
                if (channelUrl != null) {
                    DropdownMenuItem(
                        text = { Text("Don't recommend channel") },
                        leadingIcon = { Icon(Icons.Rounded.Block, null) },
                        onClick = {
                            Feedback.hideChannel(channelUrl, video.channelName, video.channelAvatar)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
