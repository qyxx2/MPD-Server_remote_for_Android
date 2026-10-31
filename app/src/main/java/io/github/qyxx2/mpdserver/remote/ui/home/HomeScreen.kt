package io.github.qyxx2.mpdserver.remote.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity
import io.github.qyxx2.mpdserver.remote.ui.library.SongActions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One root-shell owner; its LazyListStates outlive Home's NavBackStackEntry. */
internal class HomeScrollStateHolder(
    val recent: LazyListState,
    val history: LazyListState,
    val autoplay: LazyListState,
) {
    suspend fun reset() = coroutineScope {
        launch { recent.scrollToItem(0) }
        launch { history.scrollToItem(0) }
        launch { autoplay.scrollToItem(0) }
    }
}

@Composable
internal fun rememberHomeScrollStateHolder(): HomeScrollStateHolder {
    val recent = rememberLazyListState()
    val history = rememberLazyListState()
    val autoplay = rememberLazyListState()
    return remember(recent, history, autoplay) {
        HomeScrollStateHolder(recent = recent, history = history, autoplay = autoplay)
    }
}

/** Reset on every resolved non-Home route, including nested Library, Player and Queue routes. */
@Composable
internal fun HomeScrollResetEffect(
    currentRoute: String?,
    scrollStateHolder: HomeScrollStateHolder,
) {
    LaunchedEffect(currentRoute, scrollStateHolder) {
        // Also clear restored LazyListState on first composition, then on every route transition.
        scrollStateHolder.reset()
    }
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    scrollStateHolder: HomeScrollStateHolder,
    modifier: Modifier = Modifier,
    runtime: AppRuntime? = null,
    artworkIdentity: QueueResourceIdentity? = null,
    enableArtworkPrefetch: Boolean = true,
    onRefreshRecent: () -> Unit = {},
    onRefreshHistory: () -> Unit = {},
    onRefreshAutoplay: () -> Unit = {},
    homeCollectionActions: HomeCollectionActions? = null,
    songActions: SongActions? = null,
    interactionEnabled: Boolean = true,
) {
    // Each Home instance owns its visible-key registration. Multiple shell/NavHost instances can
    // coexist during transitions without replacing each other's visible artwork protection.
    val artworkVisibilityOwner = remember { Any() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("home-screen"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        HomeSection(
            title = "最近更新",
            sectionTag = "home-section-recently-updated",
            railTag = "home-rail-recently-updated",
            artworkVisibilityOwner = artworkVisibilityOwner,
            state = state.recentlyUpdated,
            scrollState = scrollStateHolder.recent,
            runtime = runtime,
            artworkIdentity = artworkIdentity,
            enableArtworkPrefetch = enableArtworkPrefetch,
            onRefresh = onRefreshRecent,
            homeCollectionActions = homeCollectionActions,
            songActions = songActions,
            interactionEnabled = interactionEnabled,
            modifier = Modifier.weight(1f),
        )
        HomeSection(
            title = "播放历史",
            sectionTag = "home-section-playback-history",
            railTag = "home-rail-playback-history",
            artworkVisibilityOwner = artworkVisibilityOwner,
            state = state.playbackHistory,
            scrollState = scrollStateHolder.history,
            runtime = runtime,
            artworkIdentity = artworkIdentity,
            enableArtworkPrefetch = enableArtworkPrefetch,
            onRefresh = onRefreshHistory,
            homeCollectionActions = homeCollectionActions,
            songActions = songActions,
            interactionEnabled = interactionEnabled,
            modifier = Modifier.weight(1f),
        )
        HomeSection(
            title = "猜你喜欢",
            sectionTag = "home-section-autoplay",
            railTag = "home-rail-autoplay",
            artworkVisibilityOwner = artworkVisibilityOwner,
            state = state.autoplay,
            scrollState = scrollStateHolder.autoplay,
            runtime = runtime,
            artworkIdentity = artworkIdentity,
            enableArtworkPrefetch = enableArtworkPrefetch,
            onRefresh = onRefreshAutoplay,
            homeCollectionActions = homeCollectionActions,
            songActions = songActions,
            interactionEnabled = interactionEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}
