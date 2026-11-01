package app.mytube.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.mytube.data.Channel
import app.mytube.data.Subscriptions
import app.mytube.ui.theme.Motion

/** Follow or unfollow a channel. Following is stored on the phone only. */
@Composable
fun SubscribeButton(channel: Channel?, modifier: Modifier = Modifier, dark: Boolean = false) {
    val list by Subscriptions.channels.collectAsState()
    val following = Subscriptions.isSubscribed(channel?.url, list)
    val haptics = LocalHapticFeedback.current
    // [dark] is for drawing over video, where the theme's colors would disappear.
    val bg by animateColorAsState(
        when {
            dark && following -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f)
            dark -> androidx.compose.ui.graphics.Color.White
            following -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.onSurface
        },
        Motion.standard(),
        label = "subscribeBg",
    )
    val fg by animateColorAsState(
        when {
            dark && following -> androidx.compose.ui.graphics.Color.White
            dark -> androidx.compose.ui.graphics.Color.Black
            following -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.surface
        },
        Motion.standard(),
        label = "subscribeFg",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = channel != null) {
                channel?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    Subscriptions.toggle(it)
                }
            }
            .animateContentSize(Motion.standard())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(following, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = fg, modifier = Modifier.padding(end = 6.dp).size(16.dp))
        }
        Text(if (following) "Subscribed" else "Subscribe", style = MaterialTheme.typography.labelLarge, color = fg)
    }
}
