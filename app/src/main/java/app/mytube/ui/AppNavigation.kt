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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mytube.ui.home.HomeScreen
import app.mytube.ui.search.SearchScreen
import app.mytube.ui.theme.Motion
import app.mytube.ui.watch.WatchScreen

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val WATCH = "watch/{url}"
    fun watch(url: String) = "watch/${Uri.encode(url)}"
}

@Composable
fun AppNavigation(incomingUrl: String?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            nav.navigate(Routes.watch(incomingUrl)) { launchSingleTop = true }
            onIncomingHandled()
        }
    }

    val openVideo: (String) -> Unit = { url -> nav.navigate(Routes.watch(url)) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
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
                            HomeScreen(onSearch = { nav.navigate(Routes.SEARCH) })
                        }
                    }
                    composable(Routes.SEARCH) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            SearchScreen(onBack = { nav.popBackStack() }, onOpenVideo = openVideo)
                        }
                    }
                    composable(
                        Routes.WATCH,
                        arguments = listOf(navArgument("url") { type = NavType.StringType }),
                    ) { entry ->
                        val url = entry.arguments?.getString("url").orEmpty()
                        CompositionLocalProvider(LocalNavScope provides this) {
                            WatchScreen(url = url, onBack = { nav.popBackStack() }, onOpenVideo = openVideo)
                        }
                    }
                }
            }
        }
    }
}
