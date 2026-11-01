package app.mytube

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import app.mytube.ui.watch.Pip
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.mytube.data.extractYouTubeUrl
import app.mytube.ui.AppNavigation
import app.mytube.ui.theme.MyTubeTheme

class MainActivity : ComponentActivity() {

    /** A YouTube link shared to or opened with the app, waiting to be shown. */
    private val incomingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingUrl.value = urlFrom(intent)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) watchPip()
        setContent {
            MyTubeTheme {
                AppNavigation(
                    incomingUrl = incomingUrl.value,
                    onIncomingHandled = { incomingUrl.value = null },
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters picture-in-picture on its own (see watchPip); older versions need a nudge.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Pip.playing) {
            runCatching { enterPictureInPictureMode(pipParams(Pip.aspect, true)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.active = isInPictureInPictureMode
    }

    /** Keeps the system's picture-in-picture settings in step with what's playing. */
    private fun watchPip() {
        lifecycleScope.launch {
            snapshotFlow { Pip.playing to Pip.aspect }.collect { (playing, aspect) ->
                runCatching { setPictureInPictureParams(pipParams(aspect, playing)) }
            }
        }
    }

    private fun pipParams(aspect: Float, autoEnter: Boolean): PictureInPictureParams {
        // Android only accepts shapes between 1:2.39 and 2.39:1.
        val clamped = aspect.coerceIn(1 / 2.3f, 2.3f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((clamped * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFrom(intent)?.let { incomingUrl.value = it }
    }

    private fun urlFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::extractYouTubeUrl)
        else -> null
    }
}
