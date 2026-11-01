package app.mytube.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mytube.ui.components.MessageState
import app.mytube.ui.components.SEARCH_BAR_KEY
import app.mytube.ui.components.VideoCard
import app.mytube.ui.components.VideoCardSkeleton
import app.mytube.ui.components.sharedMorph

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenVideo: (String) -> Unit, vm: SearchViewModel = viewModel()) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val showSuggestions = vm.editing && vm.query.isNotBlank()

    LaunchedEffect(Unit) {
        if (vm.results is SearchViewModel.Results.Idle) focus.requestFocus()
    }
    BackHandler(enabled = vm.editing && vm.results !is SearchViewModel.Results.Idle) {
        vm.onEditingChange(false)
        focusManager.clearFocus()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .sharedMorph(SEARCH_BAR_KEY)
                    .clip(RoundedCornerShape(25.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (vm.query.isEmpty()) {
                        Text(
                            "Search videos",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = vm.query,
                        onValueChange = vm::onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            vm.submit()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) vm.onEditingChange(true) },
                    )
                }
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange(""); focus.requestFocus() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        AnimatedContent(
            targetState = showSuggestions,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "searchBody",
            modifier = Modifier.weight(1f),
        ) { suggesting ->
            if (suggesting) {
                Suggestions(vm.suggestions, onPick = {
                    vm.submit(it)
                    focusManager.clearFocus()
                }, onFill = { vm.onQueryChange("$it ") })
            } else {
                ResultsList(vm.results, onOpenVideo, onRetry = { vm.submit() }, onEnd = vm::loadMore)
            }
        }
    }
}

@Composable
private fun Suggestions(items: List<String>, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().imePadding()) {
        items(items) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s) }
                    .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    s,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    maxLines = 1,
                )
                IconButton(onClick = { onFill(s) }) {
                    Icon(
                        Icons.Rounded.NorthWest,
                        contentDescription = "Use this text",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsList(
    results: SearchViewModel.Results,
    onOpenVideo: (String) -> Unit,
    onRetry: () -> Unit,
    onEnd: () -> Unit,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues()
    when (results) {
        SearchViewModel.Results.Idle -> Box(Modifier.fillMaxSize())
        SearchViewModel.Results.Loading -> Column(Modifier.fillMaxSize()) {
            repeat(3) { VideoCardSkeleton() }
        }
        is SearchViewModel.Results.Failed -> MessageState("Search failed", results.message) {
            FilledTonalButton(onClick = onRetry) { Text("Try again") }
        }
        is SearchViewModel.Results.Loaded -> {
            if (results.videos.isEmpty()) {
                MessageState("No results", "Try different words.")
                return
            }
            val state = rememberLazyListState()
            val nearEnd by remember {
                derivedStateOf {
                    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= state.layoutInfo.totalItemsCount - 4
                }
            }
            LaunchedEffect(nearEnd, results.videos.size) { if (nearEnd) onEnd() }

            LazyColumn(state = state, contentPadding = bottom, modifier = Modifier.fillMaxSize()) {
                items(results.videos, key = { it.url }) { video ->
                    VideoCard(video, onClick = { onOpenVideo(video.url) }, modifier = Modifier.animateItem())
                }
                if (results.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}
