package io.github.qyxx2.mpdserver.remote.ui.navigation

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.snapshotFlow
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
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentationHandoff
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerActions
import io.github.qyxx2.mpdserver.remote.ui.player.resolveArtistNavigationRequest
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueScreen
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueArtworkThumbnailCache
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueSongCache
import io.github.qyxx2.mpdserver.remote.ui.queue.QueuePresentation
import io.github.qyxx2.mpdserver.remote.ui.queue.QueuePresentationCacheKey
import io.github.qyxx2.mpdserver.remote.ui.queue.QueuePrefetchWindow
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity
import io.github.qyxx2.mpdserver.remote.ui.queue.createQueueSongCache
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app-scoped runtime is supplied by MainActivity; this shell observes it read-only. */
@OptIn(ExperimentalFoundationApi::class)
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
    // This transient presentation adapter is shell-scoped; all requests/retries still belong to
    // the Application-owned MutationCoordinator and survive player route disposal.
    val playerActions = remember(runtime) { PlayerActions(runtime) }
    val playerActionState by playerActions.state.collectAsStateWithLifecycle()
    LaunchedEffect(playerActions, snapshotState, activities) {
        playerActions.update(snapshotState, activities)
    }
    val currentServerOrigin = runtime.realtimeController.currentOrigin()
    val retryablePlayerIntentIds = playerActionState.activeIntentId
        ?.takeIf { playerActions.canRetryUnknown() }
        ?.let(::setOf)
        ?: emptySet()
    val retryableQueueIntentIds = activities.asSequence()
        .filter { it.intentId.startsWith("queue:") && it.status == io.github.qyxx2.mpdserver.remote.runtime.MutationStatus.UNKNOWN }
        .map { it.intentId }
        .filter(runtime.mutations::canRetryUnknown)
        .toSet()
    val statusPresentation = ActivityPresentation.from(
        snapshotState,
        activities,
        currentOrigin = currentServerOrigin,
        retryableIntentIds = retryablePlayerIntentIds + retryableQueueIntentIds,
    )
    val miniPlayerPresentation = MiniPlayerPresentation.from(snapshotState)
    val playerPresentation = remember(snapshotState) { PlayerPresentation.from(snapshotState) }

    // Mirror Web's bounded media handoff after an action: the last confirmed artwork/metadata may
    // bridge only the same-scope quiet-UNKNOWN window. Canonical playback facts and controls remain
    // tied to playerPresentation and are never promoted by this visual projection.
    val playerPresentationHandoff = remember(runtime) { PlayerPresentationHandoff() }
    var mediaHandoffTick by remember(runtime) { mutableStateOf(0) }
    val mediaProjection = remember(
        playerPresentationHandoff,
        snapshotState,
        playerPresentation,
        playerActionState,
        activities,
        mediaHandoffTick,
    ) {
        playerPresentationHandoff.project(snapshotState, playerPresentation, playerActionState, activities)
    }
    LaunchedEffect(playerPresentationHandoff, mediaProjection.expiresAtElapsedMillis) {
        val deadline = mediaProjection.expiresAtElapsedMillis ?: return@LaunchedEffect
        val delayMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        delay(delayMillis)
        mediaHandoffTick += 1
    }

    val ringPreferences by uiPreferences.statusRingPreferences.collectAsStateWithLifecycle(
        initialValue = StatusRingPreferences.DEFAULT,
    )
    val navigationScope = rememberCoroutineScope()
    val fullScreenPagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    // App-scoped for the lifetime of this navigation shell: preload once when the canonical
    // current-song identity becomes confirmed, independently of whether Player is open.
    val artworkCache = remember(navigationScope) { PlayerArtworkMemoryCache(navigationScope) }
    // Queue metadata and decoded artwork live across Queue destination disposal/re-entry, but
    // remain tied to the same app Runtime, resource identity, and one shared networking owner.
    val queueSongCache = remember(runtime, navigationScope) {
        createQueueSongCache(runtime, navigationScope)
    }
    // Warm the first client-side window while the Player remains open. The snapshot is still
    // canonical; only read-only song/artwork presentation resources are prefetched here.
    LaunchedEffect(runtime, queueSongCache, currentRoute) {
        if (currentRoute != AppDestination.FULL_SCREEN_PLAYER.route &&
            currentRoute != AppDestination.QUEUE.route
        ) return@LaunchedEffect
        runtime.snapshots.state
            // Filter playback-position-only snapshots before decoding Queue/Player facts. Mapping
            // to the full projection and applying distinctUntilChanged afterwards still repeated
            // all expensive work for every realtime tick.
            .map { state -> state to QueuePresentationCacheKey.from(state) }
            .distinctUntilChanged { previous, current -> previous.second == current.second }
            .collect { (state, _) ->
                val projection = QueuePresentation.deriveQueue(state)
                val identity = QueueResourceIdentity(
                    epoch = projection.epoch ?: "no-snapshot",
                    libraryRevision = projection.libraryRevision ?: 0L,
                    connectionGeneration = state.connectionGeneration,
                    originAddress = runtime.realtimeController.currentOrigin().address,
                )
                val rows = buildList {
                    projection.current?.let(::add)
                    addAll(projection.next)
                }
                val endExclusive = QueuePrefetchWindow.endExclusive(0, rows.size)
                val songIds = rows.take(endExclusive)
                    .mapNotNull { it.songId.takeIf(String::isNotBlank) }
                    .toSet()
                QueueArtworkThumbnailCache.updateIdentity(identity)
                queueSongCache.updateScope(identity, songIds)
                songIds.forEach(queueSongCache::load)
            }
    }
    val artworkCacheKey = PlayerArtworkCacheKey.from(
        mediaProjection.mediaPresentation,
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
    var isExitingFullScreen by remember { mutableStateOf(false) }
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
        if (isExitingFullScreen) return
        isExitingFullScreen = true
        navigationScope.launch {
            // Page 0 is the stable Player base when the whole fullscreen surface leaves.
            fullScreenPagerState.scrollToPage(0)
            if (!navController.popBackStack(rootBeforeFullScreen, inclusive = false)) {
                navController.popBackStack()
            }
            isExitingFullScreen = false
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

    BackHandler(enabled = currentRoute == AppDestination.FULL_SCREEN_PLAYER.route ||
        currentRoute == AppDestination.QUEUE.route
    ) {
        if (currentRoute == AppDestination.QUEUE.route) {
            navigationScope.launch { fullScreenPagerState.animateScrollToPage(0) }
        } else {
            exitFullScreenToRoot()
        }
    }

    // Keep Navigation's back stack aligned with the pager's settled page. During a drag the
    // pager alone owns presentation; route changes occur only after the finger-following settle.
    LaunchedEffect(currentRoute, fullScreenPagerState) {
        when (currentRoute) {
            AppDestination.FULL_SCREEN_PLAYER.route -> {
                if (fullScreenPagerState.currentPage != 0) fullScreenPagerState.scrollToPage(0)
            }
            AppDestination.QUEUE.route -> {
                if (fullScreenPagerState.currentPage != 1) fullScreenPagerState.scrollToPage(1)
            }
        }
    }
    LaunchedEffect(fullScreenPagerState) {
        snapshotFlow { fullScreenPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (isExitingFullScreen) return@collect
                when {
                    page == 1 && navController.currentDestination?.route ==
                        AppDestination.FULL_SCREEN_PLAYER.route -> {
                        navController.navigate(AppDestination.QUEUE.route) { launchSingleTop = true }
                    }
                    page == 0 && navController.currentDestination?.route == AppDestination.QUEUE.route -> {
                        navController.popBackStack(AppDestination.FULL_SCREEN_PLAYER.route, inclusive = false)
                    }
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (currentRoute == AppDestination.FULL_SCREEN_PLAYER.route ||
            currentRoute == AppDestination.QUEUE.route
        ) {
            val backdropBlurDp = FullScreenPlayerDismissPolicy.backdropBlurDp(
                entryProgress = playerEntryProgress,
                offsetPx = playerDragOffsetPx,
                screenHeightPx = playerLayerHeightPx,
                maximumBlurDp = 18f,
            )

            // The root backdrop remains aligned to the actual safe-content bounds, while its
            // fullscreen layer keeps the physical window geometry and the existing blur handoff.
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

            Box(
                modifier = Modifier.fillMaxSize().testTag("full-screen-motion-container"),
            ) {
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
                    HorizontalPager(
                        state = fullScreenPagerState,
                        modifier = Modifier.fillMaxSize().testTag("full-screen-player-queue-pager"),
                    ) { page ->
                        if (page == 0) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = Color.Transparent,
                            ) {
                                FullScreenPlayerScreen(
                                    runtime = runtime,
                                    presentation = playerPresentation,
                                    mediaPresentation = mediaProjection.mediaPresentation,
                                    mediaHandoffActive = mediaProjection.isHandoff,
                                    mediaHandoffPositionSeconds = mediaProjection.positionSeconds,
                                    actions = playerActions,
                                    artworkCacheKey = artworkCacheKey,
                                    artworkCache = artworkCache,
                                    onExit = ::exitFullScreenToRoot,
                                    onOpenQueue = {
                                        navigationScope.launch { fullScreenPagerState.animateScrollToPage(1) }
                                    },
                                    onOpenPlayer = {
                                        navigationScope.launch { fullScreenPagerState.animateScrollToPage(0) }
                                    },
                                    onArtistClick = ::requestArtistNavigation,
                                    onEntityNavigationRequest = onPlayerEntityNavigationRequest,
                                    onDismissDragDelta = { delta ->
                                        playerDragOffsetPx =
                                            (playerDragOffsetPx + delta).coerceIn(0f, playerLayerHeightPx)
                                    },
                                    onDismissGestureEnd = ::finishPlayerDismissGesture,
                                    onDismissGestureCancel = ::animatePlayerDragBack,
                                    contentPadding = safeContentPadding,
                                )
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = Color.Transparent,
                            ) {
                                QueueScreen(
                                    runtime = runtime,
                                    snapshotState = snapshotState,
                                    presentation = playerPresentation,
                                    onExit = ::exitFullScreenToRoot,
                                    onOpenPlayer = {
                                        navigationScope.launch { fullScreenPagerState.animateScrollToPage(0) }
                                    },
                                    artworkPresentation = mediaProjection.mediaPresentation,
                                    artworkCacheKey = artworkCacheKey,
                                    artworkCache = artworkCache,
                                    songCache = queueSongCache,
                                    contentPadding = safeContentPadding,
                                    onDismissDragDelta = { delta ->
                                        playerDragOffsetPx =
                                            (playerDragOffsetPx + delta).coerceIn(0f, playerLayerHeightPx)
                                    },
                                    onDismissGestureEnd = ::finishPlayerDismissGesture,
                                    onDismissGestureCancel = ::animatePlayerDragBack,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(safeContentPadding),
            ) {
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
                        playerMediaPresentation = mediaProjection.mediaPresentation,
                        playerMediaHandoffActive = mediaProjection.isHandoff,
                        playerMediaPositionSeconds = mediaProjection.positionSeconds,
                        playerActions = playerActions,
                        playerArtworkCacheKey = artworkCacheKey,
                        playerArtworkCache = artworkCache,
                        queueSongCache = queueSongCache,
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
                onRetryUnknown = { intentId ->
                    if (intentId.startsWith("player:")) {
                        if (playerActions.state.value.activeIntentId == intentId) playerActions.retryUnknown()
                    } else {
                        runtime.mutations.retryUnknown(intentId)
                    }
                },
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
    playerMediaPresentation: PlayerPresentation,
    playerMediaHandoffActive: Boolean,
    playerMediaPositionSeconds: Double?,
    playerActions: PlayerActions,
    playerArtworkCacheKey: PlayerArtworkCacheKey?,
    playerArtworkCache: PlayerArtworkMemoryCache,
    queueSongCache: QueueSongCache,
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
    queueContentPadding: PaddingValues = PaddingValues(),
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
                    mediaPresentation = playerMediaPresentation,
                    mediaHandoffActive = playerMediaHandoffActive,
                    mediaHandoffPositionSeconds = playerMediaPositionSeconds,
                    actions = playerActions,
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
                color = Color.Transparent,
            ) {
                QueueScreen(
                    runtime = runtime,
                    snapshotState = snapshotState,
                    onExit = onExitFullScreen,
                    onOpenPlayer = {
                        if (!navController.popBackStack(AppDestination.FULL_SCREEN_PLAYER.route, inclusive = false)) {
                            navController.navigate(AppDestination.FULL_SCREEN_PLAYER.route) {
                                launchSingleTop = true
                            }
                        }
                    },
                    presentation = playerPresentation,
                    artworkPresentation = playerMediaPresentation,
                    artworkCacheKey = playerArtworkCacheKey,
                    artworkCache = playerArtworkCache,
                    songCache = queueSongCache,
                    contentPadding = queueContentPadding,
                )
            }
        }
    }
}
