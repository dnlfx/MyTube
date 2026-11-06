package app.mytube.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.mytube.ui.components.LocalWatchProgress
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mytube.ui.channel.ChannelScreen
import app.mytube.ui.main.MainScreen
import app.mytube.ui.search.SearchScreen
import app.mytube.ui.theme.Motion
import app.mytube.ui.watch.miniPlayerPadding
import app.mytube.ui.watch.LocalDockPadding
import app.mytube.ui.watch.WatchHost
import app.mytube.ui.watch.WatchSheet
import app.mytube.ui.watch.rememberWatchSheetState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val CHANNEL = "channel/{url}"
    fun channel(url: String) = "channel/${Uri.encode(url)}"
}

@Composable
fun AppNavigation(incomingUrl: String?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavController()
    // The video lives above the screens rather than among them, so it can shrink into a mini
    // player and keep playing while you browse.
    val host: WatchHost = viewModel()
    val sheet = rememberWatchSheetState()

    val openVideo: (String) -> Unit = { url ->
        val wasOpen = host.watch != null
        host.open(url)
        if (wasOpen) sheet.expand() else sheet.enter()
    }
    val openChannel: (String) -> Unit = { url -> nav.navigate(Routes.channel(url)) }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            openVideo(incomingUrl)
            onIncomingHandled()
        }
    }

    val progress by app.mytube.data.History.positionsFlow.collectAsState()
    val window = androidx.compose.ui.platform.LocalConfiguration.current
    val dockPadding = if (host.watch != null) {
        miniPlayerPadding(window.screenWidthDp.toFloat(), window.screenHeightDp.toFloat()).dp
    } else {
        0.dp
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                // Nothing here shows while the video page covers it, so don't spend time drawing it.
                .graphicsLayer { alpha = if (host.watch != null && sheet.coversScreen) 0f else 1f },
        ) {
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalSharedScope provides this,
                    LocalWatchProgress provides progress,
                    LocalDockPadding provides dockPadding,
                ) {
                    NavHost(
                        navController = nav,
                        startDestination = Routes.HOME,
                        enterTransition = { fadeIn(Motion.standard()) + scaleIn(Motion.standard(), initialScale = 0.98f) },
                        exitTransition = { fadeOut(Motion.standard()) },
                        popEnterTransition = { fadeIn(Motion.standard()) },
                        popExitTransition = { fadeOut(Motion.standard()) + scaleOut(Motion.standard(), targetScale = 0.98f) },
                    ) {
                        composable(Routes.HOME) {
                            CompositionLocalProvider(LocalNavScope provides this) {
                                MainScreen(
                                    onSearch = { nav.navigate(Routes.SEARCH) },
                                    onOpenVideo = openVideo,
                                    onOpenChannel = openChannel,
                                )
                            }
                        }
                        composable(Routes.SEARCH) {
                            CompositionLocalProvider(LocalNavScope provides this) {
                                SearchScreen(onBack = { nav.popBackStack() }, onOpenVideo = openVideo, onOpenChannel = openChannel)
                            }
                        }
                        composable(
                            Routes.CHANNEL,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) {
                            CompositionLocalProvider(LocalNavScope provides this) {
                                ChannelScreen(onBack = { nav.popBackStack() }, onOpenVideo = openVideo)
                            }
                        }
                    }
                }
            }
        }
        WatchSheet(
            host = host,
            sheet = sheet,
            onOpenVideo = openVideo,
            // Like YouTube: the video tucks into the mini player while the channel opens.
            onOpenChannel = { url ->
                sheet.collapse()
                openChannel(url)
            },
        )
    }
}
