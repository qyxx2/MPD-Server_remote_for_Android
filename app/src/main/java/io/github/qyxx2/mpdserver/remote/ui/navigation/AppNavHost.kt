package io.github.qyxx2.mpdserver.remote.ui.navigation

import android.os.SystemClock
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkCacheKey
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkCacheState
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkMemoryCache
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerDismissPolicy
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
    // App-scoped for the lifetime of this navigation shell: preload once when the canonical
    // current-song identity becomes confirmed, independently of whether Player is open.
    val artworkCache = remember(navigationScope) { PlayerArtworkMemoryCache(navigationScope) }
    val artworkCacheKey = PlayerArtworkCacheKey.from(
        playerPresentation,
        connectionGeneration = snapshotState.connectionGeneration,
        epoch = snapshotState.snapshot?.epoch.orEmpty(),
        libraryRevision = snapshotState.snapshot?.libraryRevision ?: 0L,
    )
    LaunchedEffect(runtime, artworkCacheKey) {
        if (artworkCacheKey == null) artworkCache.clear()
        else artworkCache.preload(runtime, artworkCacheKey)
    }
    var showActivityStatus by remember { mutableStateOf(false) }
    var rootBeforeFullScreen by remember { mutableStateOf(AppDestination.HOME.route) }
    var playerEntryProgress by remember { mutableFloatStateOf(1f) }
    var playerDragOffsetPx by remember { mutableFloatStateOf(0f) }
    var playerLayerHeightPx by remember { mutableFloatStateOf(1f) }
    var enterPlayerFromMini by remember { mutableStateOf(false) }
    val dismissDistancePx = with(LocalDensity.current) { 120.dp.toPx() }
    val collapsedMiniHeightPx = with(LocalDensity.current) { 63.dp.toPx() }

    suspend fun awaitCurrentArtworkWarmup() {
        // Ordinarily the shell has already warmed the art. If the user opens immediately after
        // connection, finish the in-flight decode before expansion so the cover and backdrop do
        // not pop in after the full-screen route appears.
        while (true) {
            val latest = runtime.snapshots.state.value
            val latestPresentation = PlayerPresentation.from(latest)
            val key = PlayerArtworkCacheKey.from(
                latestPresentation,
                connectionGeneration = latest.connectionGeneration,
                epoch = latest.snapshot?.epoch.orEmpty(),
                libraryRevision = latest.snapshot?.libraryRevision ?: 0L,
            ) ?: return
            artworkCache.preload(runtime, key)
            val settled = artworkCache.awaitSettled(key)
            val after = runtime.snapshots.state.value
            val afterKey = PlayerArtworkCacheKey.from(
                PlayerPresentation.from(after),
                connectionGeneration = after.connectionGeneration,
                epoch = after.snapshot?.epoch.orEmpty(),
                libraryRevision = after.snapshot?.libraryRevision ?: 0L,
            )
            if (afterKey == null ||
                (afterKey == key && settled.key == key && settled !is PlayerArtworkCacheState.Loading)
            ) return
        }
    }


    LaunchedEffect(currentRoute) {
        if (currentRoute == AppDestination.FULL_SCREEN_PLAYER.route && enterPlayerFromMini) {
            playerEntryProgress = 0f
            awaitCurrentArtworkWarmup()
            if (currentRoute != AppDestination.FULL_SCREEN_PLAYER.route || !enterPlayerFromMini) {
                return@LaunchedEffect
            }
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            ) { value, _ -> playerEntryProgress = value }
            playerEntryProgress = 1f
            enterPlayerFromMini = false
        } else {
            playerEntryProgress = 1f
            enterPlayerFromMini = false
        }
        if (currentRoute != AppDestination.FULL_SCREEN_PLAYER.route) playerDragOffsetPx = 0f
    }

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

    // The full-player motion layer must be window-sized; safe insets are applied only to its
    // interactive foreground content, not to the animated surface behind transparent system bars.
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
        if (navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route) return
        if (AppDestination.rootDestinations.any { it.route == currentRoute }) {
            rootBeforeFullScreen = currentRoute!!
            playerDragOffsetPx = 0f
            playerEntryProgress = 0f
            enterPlayerFromMini = true
        }
        // Keep NavController navigation synchronous so Navigation owns the destination lifecycle.
        // The lifecycle-bound route effect below holds the Player at mini-player size until both
        // artwork bitmaps are ready, then starts the existing expansion animation.
        navController.navigate(AppDestination.FULL_SCREEN_PLAYER.route) {
            launchSingleTop = true
        }
    }

    fun animatePlayerDragBack() {
        navigationScope.launch {
            animate(
                initialValue = playerDragOffsetPx,
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) { value, _ -> playerDragOffsetPx = value }
            playerDragOffsetPx = 0f
        }
    }

    fun finishPlayerDismissGesture(totalVerticalDistancePx: Float) {
        navigationScope.launch {
            val dismiss = FullScreenPlayerDismissPolicy.shouldDismiss(
                offsetPx = playerDragOffsetPx,
                screenHeightPx = playerLayerHeightPx,
                minimumDistancePx = dismissDistancePx,
            ) && totalVerticalDistancePx > 0f
            animate(
                initialValue = playerDragOffsetPx,
                targetValue = if (dismiss) playerLayerHeightPx else 0f,
                animationSpec = if (dismiss) {
                    tween(durationMillis = 175, easing = FastOutSlowInEasing)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                },
            ) { value, _ -> playerDragOffsetPx = value }
            if (dismiss) {
                if (!navController.popBackStack(rootBeforeFullScreen, inclusive = false)) {
                    navController.popBackStack()
                }
            } else {
                playerDragOffsetPx = 0f
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
        if (currentRoute == AppDestination.FULL_SCREEN_PLAYER.route) {
            val backdropBlurDp = FullScreenPlayerDismissPolicy.backdropBlurDp(
                entryProgress = playerEntryProgress,
                offsetPx = playerDragOffsetPx,
                screenHeightPx = playerLayerHeightPx,
                maximumBlurDp = 18f,
            )

            // Keep the previous root aligned to its real safe content bounds. Its background
            // remains visible behind transparent status/navigation bars as the player moves away.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(safeContentPadding),
            ) {
                RootBackdropShell(
                    rootRoute = rootBeforeFullScreen,
                    miniPlayerPresentation = miniPlayerPresentation,
                    navController = navController,
                    uiPreferences = uiPreferences,
                    preferenceWriteScope = preferenceWriteScope,
                    windowGeometry = windowGeometry,
                    onApplyServerOrigin = applyServerOrigin,
                    blurRadiusDp = backdropBlurDp,
                    modifier = Modifier.fillMaxSize(),
                )
                // Shield only app content. Do not place a pointer interceptor in the actual
                // status-bar/cutout band, so Android's system gestures remain available.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .testTag("full-screen-root-backdrop-input-shield"),
                )
            }

            // This layer starts at the physical window top and includes both system-bar areas.
            // Consequently its background and rounded top corners follow the finger in one piece.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        playerLayerHeightPx = size.height.toFloat().coerceAtLeast(1f)
                    }
                    .graphicsLayer {
                        val entry = playerEntryProgress.coerceIn(0f, 1f)
                        val radius = FullScreenPlayerDismissPolicy.topCornerRadiusDp(
                            entryProgress = entry,
                            offsetPx = playerDragOffsetPx,
                            screenHeightPx = size.height,
                        )
                        translationY = playerDragOffsetPx
                        scaleY = FullScreenPlayerDismissPolicy.entryVerticalScale(
                            entryProgress = entry,
                            collapsedHeightPx = collapsedMiniHeightPx,
                            screenHeightPx = size.height,
                        )
                        scaleX = FullScreenPlayerDismissPolicy.entryHorizontalScale(entry)
                        transformOrigin = TransformOrigin(0.5f, 0.87f)
                        shape = RoundedCornerShape(topStart = radius.dp, topEnd = radius.dp)
                        clip = entry < 1f || playerDragOffsetPx > 0f
                    }
                    .testTag("full-screen-player-motion-layer"),
            ) {
                AppNavigationGraph(
                    navController = navController,
                    runtime = runtime,
                    snapshotState = snapshotState,
                    playerPresentation = playerPresentation,
                    playerArtworkCacheKey = artworkCacheKey,
                    playerArtworkCache = artworkCache,
                    uiPreferences = uiPreferences,
                    preferenceWriteScope = preferenceWriteScope,
                    windowGeometry = windowGeometry,
                    applyServerOrigin = applyServerOrigin,
                    onPlayerEntityNavigationRequest = onPlayerEntityNavigationRequest,
                    onArtistClick = ::requestArtistNavigation,
                    onExitFullScreen = ::exitFullScreenToRoot,
                    onOpenFullScreenPlayer = ::openFullScreenPlayer,
                    onPlayerDismissDragDelta = { delta ->
                        playerDragOffsetPx =
                            (playerDragOffsetPx + delta).coerceIn(0f, playerLayerHeightPx)
                    },
                    onPlayerDismissGestureEnd = ::finishPlayerDismissGesture,
                    onPlayerDismissGestureCancel = ::animatePlayerDragBack,
                    playerContentPadding = safeContentPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // Root destinations and Queue remain within the safe content window.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(safeContentPadding),
            ) {
                if (currentRoute == AppDestination.QUEUE.route) {
                    AppNavigationGraph(
                        navController = navController,
                        runtime = runtime,
                        snapshotState = snapshotState,
                        playerPresentation = playerPresentation,
                        playerArtworkCacheKey = artworkCacheKey,
                        playerArtworkCache = artworkCache,
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
                            playerArtworkCacheKey = artworkCacheKey,
                            playerArtworkCache = artworkCache,
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
private fun RootBackdropShell(
    rootRoute: String,
    miniPlayerPresentation: MiniPlayerPresentation,
    navController: NavHostController,
    uiPreferences: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    windowGeometry: StatusRingWindowGeometry,
    onApplyServerOrigin: suspend (ServerOrigin) -> Unit,
    blurRadiusDp: Float,
    modifier: Modifier = Modifier,
) {
    val rootTitle = AppDestination.rootDestinations.firstOrNull { it.route == rootRoute }?.label
        ?: AppDestination.HOME.label
    Box(modifier = modifier.testTag("full-screen-root-backdrop")) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .blur(blurRadiusDp.coerceAtLeast(0f).dp)
                .clearAndSetSemantics { contentDescription = "全屏播放器背景" },
        ) {
            AppShell(
            title = rootTitle,
            onStatusClick = {},
            miniPlayer = {
                if (rootRoute != AppDestination.SETTINGS.route) {
                    MiniPlayer(presentation = miniPlayerPresentation, onOpen = {})
                }
            },
            bottomBar = {
                RootNavigationBar(currentRoute = rootRoute, navController = navController)
            },
        ) {
            when (rootRoute) {
                AppDestination.SEARCH.route -> SearchPlaceholderScreen()
                AppDestination.LIBRARY.route -> LibraryPlaceholderScreen()
                AppDestination.SETTINGS.route -> StatusRingSettingsScreen(
                    preferencesRepository = uiPreferences,
                    preferenceWriteScope = preferenceWriteScope,
                    windowGeometry = windowGeometry,
                    onApplyServerOrigin = onApplyServerOrigin,
                )
                else -> HomePlaceholderScreen()
            }
        }
        }
    }
}

@Composable
private fun AppNavigationGraph(
    navController: NavHostController,
    runtime: AppRuntime,
    snapshotState: SnapshotStoreState,
    playerPresentation: PlayerPresentation,
    playerArtworkCacheKey: PlayerArtworkCacheKey?,
    playerArtworkCache: PlayerArtworkMemoryCache,
    uiPreferences: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    windowGeometry: StatusRingWindowGeometry,
    applyServerOrigin: suspend (ServerOrigin) -> Unit,
    onPlayerEntityNavigationRequest: (PlayerEntityNavigationRequest) -> Unit,
    onArtistClick: (String) -> Unit,
    onExitFullScreen: () -> Unit,
    onOpenFullScreenPlayer: () -> Unit,
    onPlayerDismissDragDelta: (Float) -> Unit = {},
    onPlayerDismissGestureEnd: (Float) -> Unit = {},
    onPlayerDismissGestureCancel: () -> Unit = {},
    playerContentPadding: PaddingValues = PaddingValues(),
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
        composable(
            route = AppDestination.FULL_SCREEN_PLAYER.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
            ) {
                FullScreenPlayerScreen(
                    runtime = runtime,
                    presentation = playerPresentation,
                    artworkCacheKey = playerArtworkCacheKey,
                    artworkCache = playerArtworkCache,
                    onExit = onExitFullScreen,
                    onOpenQueue = {
                        navController.navigate(AppDestination.QUEUE.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenPlayer = onOpenFullScreenPlayer,
                    onArtistClick = onArtistClick,
                    onEntityNavigationRequest = onPlayerEntityNavigationRequest,
                    onDismissDragDelta = onPlayerDismissDragDelta,
                    onDismissGestureEnd = onPlayerDismissGestureEnd,
                    onDismissGestureCancel = onPlayerDismissGestureCancel,
                    contentPadding = playerContentPadding,
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
