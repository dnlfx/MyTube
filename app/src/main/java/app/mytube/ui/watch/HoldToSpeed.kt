package app.mytube.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/** Press and hold a playing video to watch at 2×; letting go puts back the speed you had. */
class HoldToSpeed {
    var holding by mutableStateOf(false)
        private set
    private var player: Player? = null
    private var restore = 1f

    /** Starts 2× if [player] is playing. Returns false when there was nothing to speed up. */
    fun start(player: Player): Boolean {
        if (holding || !player.isPlaying) return false
        restore = player.playbackParameters.speed
        this.player = player
        player.setPlaybackSpeed(HOLD_SPEED)
        holding = true
        return true
    }

    fun stop() {
        if (!holding) return
        holding = false
        player?.setPlaybackSpeed(restore)
        player = null
    }

    companion object {
        const val HOLD_SPEED = 2f
    }
}

/** The "2×" pill shown at the top of the video while you hold. */
@Composable
fun HoldSpeedBadge(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("2×", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
