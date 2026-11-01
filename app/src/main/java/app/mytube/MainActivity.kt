package app.mytube

import android.content.Intent
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
        setContent {
            MyTubeTheme {
                AppNavigation(
                    incomingUrl = incomingUrl.value,
                    onIncomingHandled = { incomingUrl.value = null },
                )
            }
        }
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
