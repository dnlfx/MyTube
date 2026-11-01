package app.mytube.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mytube.data.Channel
import app.mytube.data.formatCount

/** A single scrollable row of channel matches, shown above search results. */
@Composable
fun ChannelRow(channels: List<Channel>, onOpenChannel: (String) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            "Channels",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.url }) { channel ->
                Column(
                    Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenChannel(channel.url) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(channel.avatar, 64, Modifier.sharedMorph(avatarKey(channel.url)))
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (channel.subscriberCount > 0) {
                        Text(
                            formatCount(channel.subscriberCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

fun avatarKey(url: String) = "avatar:$url"
