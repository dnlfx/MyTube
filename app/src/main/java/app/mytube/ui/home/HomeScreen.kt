package app.mytube.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.mytube.data.History
import app.mytube.data.Suggestions
import app.mytube.ui.components.SearchPill
import app.mytube.ui.components.VideoCard
import java.time.LocalTime

@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val history by History.entries.collectAsState()
    val feed by produceState(initialValue = remember { Suggestions.build(history) }, history) {
        value = withContext(Dispatchers.Default) { Suggestions.build(history) }
    }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = insets) {
        item(key = "top") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(Modifier.padding(top = 12.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark()
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Rounded.History, contentDescription = "Watch history")
                    }
                }
                Text(
                    greeting(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "No ads. No Shorts. Nothing tracked.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                SearchPill(onClick = onSearch)
                Spacer(Modifier.height(20.dp))
            }
        }

        if (feed.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Your feed starts here",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Watch a few videos and this page will fill with picks based on them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            item(key = "label") {
                Text(
                    "For you",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
            }
            items(feed, key = { it.url }, contentType = { "video" }) { video ->
                VideoCard(
                    video,
                    onClick = { onOpenVideo(video.url) },
                    onChannel = onOpenChannel,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
    Spacer(Modifier.size(8.dp))
    Text(
        buildAnnotatedString {
            append("my")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("tube") }
        },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
