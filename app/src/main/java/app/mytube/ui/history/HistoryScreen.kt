package app.mytube.ui.history

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import app.mytube.data.History
import app.mytube.ui.components.MessageState
import app.mytube.ui.components.VideoRow

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenVideo: (String) -> Unit) {
    val entries by History.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(
                "History",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
        }
        if (entries.isEmpty()) {
            MessageState("Nothing here yet", "Videos you watch show up here. History never leaves this phone.")
        }
        LazyColumn(contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            items(entries, key = { it.video.url }) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        VideoRow(entry.video, onClick = { onOpenVideo(entry.video.url) })
                    }
                    IconButton(onClick = { History.remove(entry.video.url) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove from history")
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("This also resets your home feed suggestions.") },
            confirmButton = {
                TextButton(onClick = { History.clear(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
