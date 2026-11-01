package app.mytube.ui.watch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared between the watch screen and the activity: when a video is playing, leaving the app
 * shrinks it into a floating picture-in-picture window instead of stopping it.
 */
object Pip {
    /** True while a video is playing on screen, so leaving the app should keep it going. */
    var playing by mutableStateOf(false)
    /** Width over height of the playing video, for the window's shape. */
    var aspect by mutableFloatStateOf(16f / 9f)
    /** The player that last reported, so a closing screen doesn't clear a newer one's state. */
    var owner: Any? = null
    /** True while the app is shown as the floating window. */
    var active by mutableStateOf(false)
}
