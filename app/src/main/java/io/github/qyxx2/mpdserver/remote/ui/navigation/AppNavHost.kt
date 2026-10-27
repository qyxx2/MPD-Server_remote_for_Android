package io.github.qyxx2.mpdserver.remote.ui.navigation

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityStatusSheet
import io.github.qyxx2.mpdserver.remote.ui.components.GlobalStatusRingOverlay
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingVisibilityController
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerScreen
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerEntityNavigationRequest
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.player.resolveArtistNavigationRequest
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueDestinationPlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.HomePlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.LibraryPlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.SearchPlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.StatusRingSettingsScreen
import io.github.qyxx2.mpdserver.remote.ui.shell.AppShell
import io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayer
import io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.shell.RootNavigationBar
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The app-scoped runtime is supplied by MainActivity; this shell observes it read-only. */
@Composable
fun AppNavHost(
    runtime: AppRuntime,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    windowGeometry: StatusRingWindowGeometry = StatusRingWindowGeometry(),
    uiPreferences: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    applyServerOrigin: suspend (ServerOrigin) -> Unit = { origin ->
        uiPreferences.setServerOrigin(origin)
        runtime.changeOrigin(origin)
    },
    onPlayerEntityNavigationRequest: (PlayerEntityNavigationRequest) -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snapshotState by runtime.snapshots.state.collectAsStateWithLifecycle()
    // Observe the existing shared coordinator; this does not start networking or submit a mutation.
    val activities by runtime.mutations.activities.collectAsStateWithLifecycle()
    val statusPresentation = ActivityPresentation.from(snapshotState, activities)
    val miniPlayerPresentation = MiniPlayerPresentation.from(snapshotState)
    val playerPresentation = remember(snapshotState) { PlayerPresentation.from(snapshotState) }
    val ringPreferences by uiPreferences.statusRingPreferences.collectAsStateWithLifecycle(
        initialValue = StatusRingPreferences.DEFAULT,
    )
    val navigationScope = rememberCoroutineScope()
    var showActivityStatus by remember { mutableStateOf(false) }
    var rootBeforeFullScreen by remember { mutableStateOf(AppDestination.HOME.route) }

    LaunchedEffect(currentRoute) {
        if (AppDestination.rootDestinations.any { it.route == currentRoute }) {
            rootBeforeFullScreen = currentRoute!!
        }
    }

    val visibilityController = remember { StatusRingVisibilityController() }
    var ringVisibility by remember {
        mutableStateOf(visibilityController.update(activities, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(activities) {
        ringVisibility = visibilityController.update(activities, SystemClock.elapsedRealtime())
    }
    LaunchedEffect(ringVisibility.hideAtMillis) {
        val deadline = ringVisibility.hideAtMillis ?: return@LaunchedEffect
        val remainingMillis = deadline - SystemClock.elapsedRealtime()
        if (remainingMillis > 0L) delay(remainingMillis)
        ringVisibility = visibilityController.onTimer(SystemClock.elapsedRealtime())
    }

    val fullScreen = currentRoute == AppDestination.FULL_SCREEN_PLAYER.route ||
        currentRoute == AppDestination.QUEUE.route
    val title = AppDestination.rootDestinations
        .firstOrNull { it.route == currentRoute }
        ?.label
        ?: AppDestination.HOME.label
    val safeInsets = windowGeometry.safeInsets
    val safeContentPadding = with(LocalDensity.current) {
        PaddingValues(
            start = safeInsets.left.coerceAtLeast(0).toDp(),
            top = safeInsets.top.coerceAtLeast(0).toDp(),
            end = safeInsets.right.coerceAtLeast(0).toDp(),
            bottom = safeInsets.bottom.coerceAtLeast(0).toDp(),
        )
    }

    fun openFullScreenPlayer() {
        if (AppDestination.rootDestinations.any { it.route == currentRoute }) {
            rootBeforeFullScreen = currentRoute!!
        }
        if (currentRoute != AppDestination.FULL_SCREEN_PLAYER.route) {
            navController.navigate(AppDestination.FULL_SCREEN_PLAYER.route) {
                launchSingleTop = true
            }
        }
    }

    fun exitFullScreenToRoot() {
        if (!navController.popBackStack(rootBeforeFullScreen, inclusive = false)) {
            navController.popBackStack()
        }
    }

    fun requestArtistNavigation(displayName: String) {
        val startState: SnapshotStoreState = runtime.snapshots.state.value
        val sourceEpoch = startState.snapshot?.epoch ?: return
        val sourceGeneration = startState.connectionGeneration
        val sourceSongId = PlayerPresentation.from(startState).song?.songId ?: return
        if (!startState.controlsEligible) return

        navigationScope.launch {
            when (val result = runtime.reads.getArtists()) {
                is ApiResult.Success -> {
                    val latest = runtime.snapshots.state.value
                    val stillSameCurrent = navController.currentDestination?.route ==
                        AppDestination.FULL_SCREEN_PLAYER.route &&
                        latest.controlsEligible &&
                        latest.connectionGeneration == sourceGeneration &&
                        latest.snapshot?.epoch == sourceEpoch &&
                        PlayerPresentation.from(latest).song?.songId == sourceSongId
                    if (stillSameCurrent) {
                        resolveArtistNavigationRequest(displayName, result.value)
                            ?.let(onPlayerEntityNavigationRequest)
                    }
                }
                else -> Unit // No speculative route or local artist-list substitute on a failed read.
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(safeContentPadding),
        ) {
            if (fullScreen) {
                AppNavigationGraph(
                    navController = navController,
                    runtime = runtime,
                    snapshotState = snapshotState,
                    playerPresentation = playerPresentation,
                    uiPreferences = uiPreferences,
                    preferenceWriteScope = preferenceWriteScope,
                    windowGeometry = windowGeometry,
                    applyServerOrigin = applyServerOrigin,
                    onPlayerEntityNavigationRequest = onPlayerEntityNavigationRequest,
                    onArtistClick = ::requestArtistNavigation,
                    onExitFullScreen = ::exitFullScreenToRoot,
                    onOpenFullScreenPlayer = ::openFullScreenPlayer,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AppShell(
                    title = title,
                    onStatusClick = { showActivityStatus = true },
                    miniPlayer = {
                        if (currentRoute != AppDestination.SETTINGS.route) {
                            MiniPlayer(
                                presentation = miniPlayerPresentation,
                                onOpen = ::openFullScreenPlayer,
                            )
                        }
                    },
                    bottomBar = {
                        RootNavigationBar(
                            currentRoute = currentRoute,
                            navController = navController,
                        )
                    },
                ) {
                    AppNavigationGraph(
                        navController = navController,
                        runtime = runtime,
                        snapshotState = snapshotState,
                        playerPresentation = playerPresentation,
                        uiPreferences = uiPreferences,
                        preferenceWriteScope = preferenceWriteScope,
                        windowGeometry = windowGeometry,
                        applyServerOrigin = applyServerOrigin,
                        onPlayerEntityNavigationRequest = onPlayerEntityNavigationRequest,
                        onArtistClick = ::requestArtistNavigation,
                        onExitFullScreen = ::exitFullScreenToRoot,
                        onOpenFullScreenPlayer = ::openFullScreenPlayer,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (ringVisibility.visible) {
            GlobalStatusRingOverlay(
                presentation = statusPresentation,
                geometry = windowGeometry,
                preferences = ringPreferences,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showActivityStatus) {
            ActivityStatusSheet(
                presentation = statusPresentation,
                onDismiss = { showActivityStatus = false },
            )
        }
    }
}

@Composable
private fun AppNavigationGraph(
    navController: NavHostController,
    runtime: AppRuntime,
    snapshotState: SnapshotStoreState,
    playerPresentation: PlayerPresentation,
    uiPreferences: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    windowGeometry: StatusRingWindowGeometry,
    applyServerOrigin: suspend (ServerOrigin) -> Unit,
    onPlayerEntityNavigationRequest: (PlayerEntityNavigationRequest) -> Unit,
    onArtistClick: (String) -> Unit,
    onExitFullScreen: () -> Unit,
    onOpenFullScreenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.HOME.route,
        modifier = modifier,
    ) {
        composable(AppDestination.HOME.route) { HomePlaceholderScreen() }
        composable(AppDestination.SEARCH.route) { SearchPlaceholderScreen() }
        composable(AppDestination.LIBRARY.route) { LibraryPlaceholderScreen() }
        composable(AppDestination.SETTINGS.route) {
            StatusRingSettingsScreen(
                preferencesRepository = uiPreferences,
                preferenceWriteScope = preferenceWriteScope,
                windowGeometry = windowGeometry,
                onApplyServerOrigin = applyServerOrigin,
            )
        }
        composable(AppDestination.FULL_SCREEN_PLAYER.route) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                FullScreenPlayerScreen(
                    runtime = runtime,
                    presentation = playerPresentation,
                    onExit = onExitFullScreen,
                    onOpenQueue = {
                        navController.navigate(AppDestination.QUEUE.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenPlayer = onOpenFullScreenPlayer,
                    onArtistClick = onArtistClick,
                    onEntityNavigationRequest = onPlayerEntityNavigationRequest,
                )
            }
        }
        composable(AppDestination.QUEUE.route) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                QueueDestinationPlaceholderScreen(
                    onExit = onExitFullScreen,
                    onOpenPlayer = {
                        if (!navController.popBackStack(AppDestination.FULL_SCREEN_PLAYER.route, inclusive = false)) {
                            navController.navigate(AppDestination.FULL_SCREEN_PLAYER.route) {
                                launchSingleTop = true
                            }
                        }
                    },
                    presentation = playerPresentation,
                )
            }
        }
    }
}
