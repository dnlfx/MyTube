package app.mytube.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mytube.ui.home.HomeScreen
import app.mytube.ui.library.LibraryScreen
import app.mytube.ui.shorts.ShortsScreen
import app.mytube.ui.subscriptions.SubscriptionsScreen
import app.mytube.ui.theme.Motion
import app.mytube.ui.watch.WatchDock
import androidx.compose.ui.layout.onSizeChanged

enum class MainTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    SHORTS("Shorts", Icons.Outlined.Bolt, Icons.Rounded.Bolt),
    SUBSCRIPTIONS("Subscriptions", Icons.Outlined.Subscriptions, Icons.Rounded.Subscriptions),
    LIBRARY("Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
}

/** The app's four main views, switched with the bar at the bottom. */
@Composable
fun MainScreen(
    onSearch: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    // Each tab keeps its own scroll position while you switch between them.
    val saved = rememberSaveableStateHolder()
    val shorts = tab == MainTab.SHORTS
    // Back from another tab returns home before it leaves the app.
    androidx.activity.compose.BackHandler(enabled = tab != MainTab.HOME) { tab = MainTab.HOME }
    // Get Shorts ready in the background so the tab opens without a long wait.
    androidx.compose.runtime.LaunchedEffect(Unit) { app.mytube.data.Shorts.warm() }
    // The mini player docks above the tab bar, and steps aside while Shorts plays.
    androidx.compose.runtime.SideEffect { WatchDock.shorts = shorts }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            WatchDock.barHeight = 0
            WatchDock.shorts = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.standard()) },
            label = "mainTab",
            modifier = Modifier.weight(1f),
        ) { current ->
            saved.SaveableStateProvider(current.name) {
                when (current) {
                    MainTab.HOME -> HomeScreen(onSearch = onSearch, onOpenVideo = onOpenVideo, onOpenChannel = onOpenChannel)
                    MainTab.SHORTS -> ShortsScreen(onOpenChannel = onOpenChannel)
                    MainTab.SUBSCRIPTIONS -> SubscriptionsScreen(onSearch, onOpenVideo, onOpenChannel)
                    MainTab.LIBRARY -> LibraryScreen(onSearch, onOpenVideo, onOpenChannel)
                }
            }
        }
        BottomBar(
            tab,
            dark = shorts,
            onSelect = { tab = it },
            modifier = Modifier.onSizeChanged { WatchDock.barHeight = it.height },
        )
    }
}

@Composable
private fun BottomBar(selected: MainTab, dark: Boolean, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    // Over Shorts the bar turns black so the video feels full screen.
    val bg by animateColorAsState(
        if (dark) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow,
        Motion.standard(),
        label = "barBg",
    )
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth().background(bg)) {
        if (!dark) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                val active = tab == selected
                val fg by animateColorAsState(
                    when {
                        dark && active -> Color.White
                        dark -> Color.White.copy(alpha = 0.6f)
                        active -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    Motion.standard(),
                    label = "tabFg",
                )
                val pill by animateFloatAsState(if (active) 1f else 0f, Motion.expressive(), label = "tabPill")
                val pillColor = if (dark) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = active,
                            onClick = {
                                if (!active) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(tab)
                            },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(56.dp)
                                .height(30.dp)
                                .graphicsLayer { scaleX = 0.6f + 0.4f * pill; alpha = pill }
                                .clip(RoundedCornerShape(50))
                                .background(pillColor),
                        )
                        Icon(
                            if (active) tab.selectedIcon else tab.icon,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
