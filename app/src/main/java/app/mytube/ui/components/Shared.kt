package app.mytube.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mytube.ui.LocalNavScope
import app.mytube.ui.LocalSharedScope
import app.mytube.ui.theme.Motion

/** Morphs an element between screens (for example, a thumbnail growing into the player). */
@Composable
fun Modifier.sharedMorph(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedMorph.sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = nav,
            boundsTransform = { _, _ -> Motion.expressive() },
        )
    }
}
