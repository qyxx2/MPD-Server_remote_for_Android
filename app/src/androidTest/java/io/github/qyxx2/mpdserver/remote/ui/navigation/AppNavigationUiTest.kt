package io.github.qyxx2.mpdserver.remote.ui.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingInsets
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingRect
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.components.GlobalStatusRingOverlay
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingDraftPreviewOverlay
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerAction
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerActions
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityStatusContent
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityRowPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityVisualState
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityStatusSheet
import io.github.qyxx2.mpdserver.remote.ui.settings.createRepositoryLinkIntent
import io.github.qyxx2.mpdserver.remote.ui.settings.AboutScreen
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AppNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var runtime: AppRuntime
    private var savedServerOriginAddress: String? = null
    private var savedServerOrigin: ServerOrigin? = null
    private var savedThemePreference: ThemePreference? = null
    private var savedStatusRingPreferences: StatusRingPreferences? = null

    @Before
    fun capturePersistedPreferences() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        runBlocking {
            savedServerOriginAddress = application.uiPreferences.serverOrigin.first()
            savedServerOrigin = ServerOrigin.parseAddress(requireNotNull(savedServerOriginAddress))
            savedThemePreference = application.uiPreferences.themePreference.first()
            savedStatusRingPreferences = application.uiPreferences.statusRingPreferences.first()
        }
    }

    @After
    fun restorePersistedPreferences() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        runBlocking {
            val origin = requireNotNull(savedServerOrigin)
            val address = requireNotNull(savedServerOriginAddress)
            val theme = requireNotNull(savedThemePreference)
            val ring = requireNotNull(savedStatusRingPreferences)

            if (application.uiPreferences.themePreference.first() != theme) {
                application.uiPreferences.setThemePreference(theme)
            }
            if (application.uiPreferences.statusRingPreferences.first() != ring) {
                application.uiPreferences.setStatusRingPreferences(ring)
            }
            if (application.uiPreferences.serverOrigin.first() != address ||
                application.runtime.realtimeController.currentOrigin().address != address
            ) {
                application.saveAndApplyServerOrigin(origin)
            }

            assertEquals("Instrumentation must preserve the user's saved MS server address", address,
                application.uiPreferences.serverOrigin.first())
            assertEquals("Instrumentation must preserve the user's theme", theme,
                application.uiPreferences.themePreference.first())
            assertEquals("Instrumentation must preserve the user's status-ring preferences", ring,
                application.uiPreferences.statusRingPreferences.first())
        }
    }

    private fun assertMiniPlayerControlMatchesCanonicalState() {
        val actions = io.github.qyxx2.mpdserver.remote.ui.player.PlayerActions(runtime)
        val enabled = actions.isEnabled(PlayerAction.PAUSE) || actions.isEnabled(PlayerAction.RESUME)
        val control = composeRule.onNodeWithTag("mini-player-play-pause")
        if (enabled) control.assertIsEnabled() else control.assertIsNotEnabled()
    }

    private fun launchNavigation(geometry: StatusRingWindowGeometry = StatusRingWindowGeometry()) {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        runBlocking { application.uiPreferences.resetStatusRingPreferences() }
        runtime = application.runtime
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            AppNavHost(
                runtime = runtime,
                navController = controller,
                uiPreferences = application.uiPreferences,
                preferenceWriteScope = application.uiPreferencesWriteScope,
                applyServerOrigin = application::saveAndApplyServerOrigin,
                windowGeometry = geometry,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rootDestinationsRenderInFrozenOrderAndTrackSelectedRoute() {
        launchNavigation()
        val destinations = AppDestination.rootDestinations
        assertEquals(listOf("主页", "搜索", "音乐库", "设置"), destinations.map { it.label })

        val tags = destinations.map { "root-nav-${it.route}" }
        val leftPositions = tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.left }
        assertTrue("Root navigation must render in the frozen left-to-right order", leftPositions.zipWithNext().all { it.first < it.second })

        destinations.forEach { destination ->
            val tag = "root-nav-${destination.route}"
            // The app bar and navigation can legitimately repeat the root label (e.g. “主页”).
            // Assert the uniquely tagged navigation item instead of an ambiguous text query.
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.waitForIdle()
            assertEquals(destination.route, navController.currentDestination?.route)
            composeRule.onNodeWithTag(tag).assertIsSelected()
        }
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)
    }

    @Test
    fun settingsRootShowsExactlyFourCategoriesAndNestedPagesReturnToSettings() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-root-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-root-screen").onChildren().assertCountEquals(4)
        val categories = listOf(
            Triple("status", "状态", "settings/status"),
            Triple("server", "服务器", "settings/server"),
            Triple("appearance", "外观", "settings/appearance"),
            Triple("about", "关于", "settings/about"),
        )
        categories.forEachIndexed { index, (id, title, route) ->
            composeRule.onNodeWithTag("settings-category-$id").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertEquals(route, navController.currentDestination?.route)
            composeRule.onNodeWithTag("app-bar-title").assertTextEquals(title)
            composeRule.onNodeWithTag("settings-child-back").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
            composeRule.onNodeWithTag("root-nav-settings").assertIsSelected()
            val pageTag = when (id) {
                "status" -> "status-settings-screen"
                "server" -> "server-settings-screen"
                "appearance" -> "appearance-settings-screen"
                else -> "about-settings-screen"
            }
            composeRule.onNodeWithTag(pageTag).assertIsDisplayed()

            if (index == 0) {
                composeRule.onNodeWithTag("settings-child-back").performClick()
            } else {
                composeRule.runOnIdle {
                    composeRule.activity.onBackPressedDispatcher.onBackPressed()
                }
            }
            composeRule.waitForIdle()
            assertEquals(AppDestination.SETTINGS.route, navController.currentDestination?.route)
            composeRule.onNodeWithTag("settings-root-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("root-nav-settings").assertIsSelected()
        }
    }

    @Test
    fun appBarDoesNotExposeActivityEntryOnAnyRootPage() {
        launchNavigation()
        AppDestination.rootDestinations.forEach { destination ->
            composeRule.onNodeWithTag("root-nav-${destination.route}").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("app-bar").assertIsDisplayed()
            composeRule.onAllNodesWithTag("activity-details-entry").assertCountEquals(0)
        }
    }

    @Test
    fun serverAddressHelpIconTogglesExistingFormatHint() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-server").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("server-origin-input").assertIsDisplayed()
        composeRule.onAllNodesWithTag("server-origin-supporting-text").assertCountEquals(0)
        composeRule.onNodeWithTag("server-origin-help-toggle").performClick()
        composeRule.onNodeWithTag("server-origin-supporting-text").assertIsDisplayed()
        composeRule.onNodeWithText(
            "支持 IPv4、IPv6 和域名；可输入完整 http(s):// 地址。省略协议使用 HTTP，省略端口默认 18000；IPv6 自定义端口请写成 [地址]:端口。",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("server-origin-help-toggle").performClick()
        composeRule.onAllNodesWithTag("server-origin-supporting-text").assertCountEquals(0)
    }

    @Test
    fun serverAddressPageHasOnlyOneLineInputAndSaveAction() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-server").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("server-origin-input").assertIsDisplayed()
        composeRule.onAllNodesWithTag("server-origin-save").assertCountEquals(1)
        composeRule.onAllNodesWithTag("server-origin-current").assertCountEquals(0)
        composeRule.onAllNodesWithText("当前配置：").assertCountEquals(0)
    }

    @Test
    fun aboutPageShowsRepositoryAndAuthorAndUsesRepositoryViewIntent() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-about").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("about-app-name").assertIsDisplayed()
        composeRule.onNodeWithTag("about-introduction").assertIsDisplayed()
        composeRule.onNodeWithTag("about-author").assertIsDisplayed()
        composeRule.onNodeWithTag("about-version").assertIsDisplayed()
        composeRule.onNodeWithTag("about-repository-link").assertIsDisplayed()
        composeRule.onNodeWithText("https://github.com/qyxx2/MPD-Server_remote_for_Android").assertIsDisplayed()

        val repositoryIntent = createRepositoryLinkIntent()
        assertEquals(Intent.ACTION_VIEW, repositoryIntent.action)
        assertEquals(
            "https://github.com/qyxx2/MPD-Server_remote_for_Android",
            repositoryIntent.dataString,
        )
    }

    @Test
    fun aboutRepositoryButtonInvokesTheOpenAction() {
        var openRequested = false
        composeRule.setContent {
            AboutScreen(onOpenRepository = { openRequested = true })
        }

        composeRule.onNodeWithTag("about-repository-link").performClick()
        composeRule.runOnIdle { assertTrue(openRequested) }
        val intent = createRepositoryLinkIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/qyxx2/MPD-Server_remote_for_Android", intent.dataString)
    }

    @Test
    fun appearancePreviewUsesTheSameProductionWindowPlacementForCutoutAndManualFallback() {
        val screenWidth = composeRule.activity.resources.displayMetrics.widthPixels
        val geometries = listOf(
            StatusRingWindowGeometry(
                cutoutBounds = listOf(
                    StatusRingRect(
                        left = screenWidth / 2 - 36,
                        top = 0,
                        right = screenWidth / 2 + 36,
                        bottom = 72,
                    ),
                ),
                safeInsets = StatusRingInsets(left = 0, top = 120, right = 0, bottom = 72),
            ),
            StatusRingWindowGeometry(
                cutoutBounds = emptyList(),
                safeInsets = StatusRingInsets(left = 0, top = 120, right = 0, bottom = 72),
            ),
        )
        val presentation = ActivityPresentation(
            visualState = ActivityVisualState.INFO,
            statusLabel = "预览对照",
            connectionLabel = "预览",
            freshnessLabel = "预览",
            readError = null,
            activities = emptyList(),
            isConfirmedAvailable = false,
        )
        val geometryState = androidx.compose.runtime.mutableStateOf(geometries.first())

        composeRule.setContent {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                GlobalStatusRingOverlay(
                    presentation = presentation,
                    geometry = geometryState.value,
                    preferences = StatusRingPreferences.DEFAULT,
                    modifier = Modifier.fillMaxSize(),
                )
                StatusRingDraftPreviewOverlay(
                    geometry = geometryState.value,
                    preferences = StatusRingPreferences.DEFAULT,
                )
            }
        }
        geometries.forEach { geometry ->
            composeRule.runOnIdle { geometryState.value = geometry }
            composeRule.waitForIdle()
            val production = composeRule.onNodeWithTag("status-ring-visual")
                .fetchSemanticsNode().boundsInRoot
            val draft = composeRule.onNodeWithTag("status-ring-draft-preview-visual")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(production.center.x, draft.center.x, 0.5f)
            assertEquals(production.center.y, draft.center.y, 0.5f)
            assertEquals(production.width, draft.width, 0.5f)
            assertEquals(production.height, draft.height, 0.5f)
        }
    }

    @Test
    fun statusPageShowsCanonicalConnectionFreshnessAndEmptyActivityState() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-status").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("status-settings-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-connection").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-freshness").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-empty").assertIsDisplayed()
    }

    @Test
    fun statusContentPreservesPendingAndFailedActivityRowsAndTheirOrder() {
        val presentation = ActivityPresentation(
            visualState = ActivityVisualState.FAILED,
            statusLabel = "操作失败",
            connectionLabel = "已连接",
            freshnessLabel = "最新",
            readError = null,
            activities = listOf(
                ActivityRowPresentation(
                    intentId = "queue:failed-17",
                    title = "更新播放队列",
                    statusLabel = "操作失败",
                    message = "服务端拒绝了队列更新",
                    syncError = "write rejected",
                    canRetry = false,
                ),
                ActivityRowPresentation(
                    intentId = "player:pending-16",
                    title = "切换播放曲目",
                    statusLabel = "等待处理",
                    message = "等待服务端确认",
                    syncError = null,
                    canRetry = false,
                ),
            ),
            isConfirmedAvailable = true,
        )
        composeRule.setContent {
            ActivityStatusContent(presentation = presentation)
        }

        composeRule.onNodeWithText("更新播放队列").assertIsDisplayed()
        composeRule.onNodeWithText("操作失败").assertIsDisplayed()
        composeRule.onNodeWithText("服务端拒绝了队列更新").assertIsDisplayed()
        composeRule.onNodeWithText("同步错误：write rejected").assertIsDisplayed()
        composeRule.onNodeWithText("切换播放曲目").assertIsDisplayed()
        composeRule.onNodeWithText("等待处理").assertIsDisplayed()
        composeRule.onNodeWithText("等待服务端确认").assertIsDisplayed()

        val failedTop = composeRule.onNodeWithTag("activity-row-queue:failed-17")
            .fetchSemanticsNode().boundsInRoot.top
        val pendingTop = composeRule.onNodeWithTag("activity-row-player:pending-16")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("Status rows must preserve the canonical newest-first order", failedTop < pendingTop)
    }

    @Test
    fun statusActivityRetryPreservesTheOriginalUnknownIntentId() {
        val originalIntentId = "queue:original-unknown-intent-17"
        var retriedIntentId: String? = null
        val presentation = ActivityPresentation(
            visualState = ActivityVisualState.UNKNOWN,
            statusLabel = "结果未知",
            connectionLabel = "已连接",
            freshnessLabel = "最新",
            readError = null,
            activities = listOf(
                ActivityRowPresentation(
                    intentId = originalIntentId,
                    title = "调整播放队列",
                    statusLabel = "结果未知",
                    message = "等待服务端确认",
                    syncError = null,
                    canRetry = true,
                ),
            ),
            isConfirmedAvailable = true,
        )
        composeRule.setContent {
            ActivityStatusSheet(
                presentation = presentation,
                onDismiss = {},
                onRetryUnknown = { retriedIntentId = it },
            )
        }
        composeRule.onNodeWithTag("activity-retry-$originalIntentId").performClick()
        composeRule.waitForIdle()
        assertEquals(originalIntentId, retriedIntentId)
    }

    @Test
    fun settingsCanSelectAndPersistAllThemePreferences() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-appearance").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("主题").assertIsDisplayed()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val options = listOf(
            "theme-option-follow-system" to ThemePreference.FOLLOW_SYSTEM,
            "theme-option-light" to ThemePreference.LIGHT,
            "theme-option-dark" to ThemePreference.DARK,
        )

        options.forEach { (tag, preference) ->
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.waitForIdle()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runBlocking { application.uiPreferences.themePreference.first() } == preference
            }
            composeRule.onNodeWithTag(tag).assertIsSelected()
        }

        runBlocking { application.uiPreferences.setThemePreference(ThemePreference.FOLLOW_SYSTEM) }
    }

    @Test
    fun selectingCurrentRootDoesNotReplaceItsBackStackEntryOrRuntimeOwner() {
        launchNavigation()
        val homeTag = "root-nav-${AppDestination.HOME.route}"
        composeRule.onNodeWithTag(homeTag).performClick()
        composeRule.waitForIdle()
        val selectedEntry = navController.currentBackStackEntry

        composeRule.onNodeWithTag(homeTag).performClick()
        composeRule.waitForIdle()

        assertSame(selectedEntry, navController.currentBackStackEntry)
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)
    }

    @Test
    fun rootPagesShowSharedAppBarScrollableContentMiniPlayerAndNavigation() {
        launchNavigation()

        composeRule.onNodeWithTag("app-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bar-title").assertIsDisplayed()
        composeRule.onAllNodesWithTag("activity-details-entry").assertCountEquals(0)
        composeRule.onAllNodesWithTag("status-ring-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("root-content").assertIsDisplayed()
        listOf(
            "home-rail-recently-updated",
            "home-rail-playback-history",
            "home-rail-autoplay",
        ).forEach { railTag ->
            assertTrue(
                "Each Home section must expose its own horizontal scroll container: $railTag",
                composeRule.onNodeWithTag(railTag).fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
            )
        }
        composeRule.onNodeWithTag("root-navigation-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-body").assertIsDisplayed()
        assertMiniPlayerControlMatchesCanonicalState()

        val contentBounds = composeRule.onNodeWithTag("root-content").fetchSemanticsNode().boundsInRoot
        val miniBounds = composeRule.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        val navigationBounds = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Root content must end above the mini-player", contentBounds.bottom <= miniBounds.top + 1f)
        assertTrue("Mini-player must end above the fixed navigation", miniBounds.bottom <= navigationBounds.top + 1f)

    }

    @Test
    fun allRootPagesUseTheSharedShellAndSettingsDoesNotAddMiniPlayerSpace() {
        launchNavigation()
        AppDestination.rootDestinations.forEach { destination ->
            composeRule.onNodeWithTag("root-nav-${destination.route}").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("app-bar").assertIsDisplayed()
            composeRule.onNodeWithTag("app-bar-title").assertIsDisplayed()
            composeRule.onNodeWithTag("root-content").assertIsDisplayed()
            composeRule.onNodeWithTag("root-navigation-bar").assertIsDisplayed()
            when (destination) {
                AppDestination.HOME -> {
                    composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
                    listOf(
                        "home-rail-recently-updated",
                        "home-rail-playback-history",
                        "home-rail-autoplay",
                    ).forEach { railTag ->
                        assertTrue(
                            "Home must retain three independently scrollable rails: $railTag",
                            composeRule.onNodeWithTag(railTag).fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
                        )
                    }
                }
                AppDestination.SEARCH -> composeRule.onNodeWithTag("search-screen").assertIsDisplayed()
                AppDestination.LIBRARY -> {
                    composeRule.onNodeWithTag("library-root-screen").assertIsDisplayed()
                    assertTrue(
                        "Library category list must remain scrollable",
                        composeRule.onNodeWithTag("library-category-list").fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
                    )
                }
                AppDestination.SETTINGS -> {
                    composeRule.onNodeWithTag("settings-root-screen").assertIsDisplayed()
                    listOf("status", "server", "appearance", "about").forEach { category ->
                        composeRule.onNodeWithTag("settings-category-$category").assertIsDisplayed()
                    }
                }
                AppDestination.FULL_SCREEN_PLAYER, AppDestination.QUEUE ->
                    error("Full-screen destinations are not root navigation entries")
            }
            if (destination == AppDestination.SETTINGS) {
                composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
            } else {
                composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
                assertMiniPlayerControlMatchesCanonicalState()
            }
            val navBounds = composeRule.onNodeWithTag("root-nav-${destination.route}").fetchSemanticsNode().boundsInRoot
            val minTarget = with(composeRule.density) { 48.dp.toPx() }
            assertTrue("Root navigation items must retain 48dp touch targets", navBounds.height >= minTarget)
            assertEquals(destination.route, navController.currentDestination?.route)
        }
    }

    @Test
    fun serverOriginSettingsRejectsInvalidInputAndAppliesValidAddressToSharedRuntime() {
        launchNavigation()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val defaultOrigin = ServerOrigin.parseAddress(ServerOrigin.DEFAULT_ADDRESS)
        runBlocking { application.saveAndApplyServerOrigin(defaultOrigin) }

        try {
            composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings-category-server").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("server-origin-settings-title").assertIsDisplayed()
            composeRule.onNodeWithTag("server-origin-input").performScrollTo()
            val serverOriginField = composeRule.onNodeWithTag("server-origin-input")
            serverOriginField.performTextClearance()
            serverOriginField.performTextInput("ms.example.com/api")
            composeRule.onNodeWithTag("server-origin-save").performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("server-origin-supporting-text")
                .assertTextEquals("地址格式不正确：请检查主机、端口、协议和路径。")
            assertEquals(
                ServerOrigin.DEFAULT_ADDRESS,
                runBlocking { application.uiPreferences.serverOrigin.first() },
            )
            assertEquals(ServerOrigin.DEFAULT_ADDRESS, application.runtime.realtimeController.currentOrigin().address)

            serverOriginField.performTextClearance()
            serverOriginField.performTextInput("192.168.1.20")
            composeRule.onNodeWithTag("server-origin-save").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runBlocking { application.uiPreferences.serverOrigin.first() } == "http://192.168.1.20:18000" &&
                    application.runtime.realtimeController.currentOrigin().address == "http://192.168.1.20:18000"
            }
            composeRule.onNodeWithTag("server-origin-status")
                .assertTextEquals("已保存并应用；尚未检测服务器连通性")
        } finally {
            runBlocking { application.saveAndApplyServerOrigin(defaultOrigin) }
        }
    }

    @Test
    fun settingsShowsFourRingControlsPreviewAndNoMiniPlayer() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-appearance").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("appearance-settings-screen").assertIsDisplayed()
        composeRule.onNodeWithText("状态环").assertIsDisplayed()
        listOf(
            "status-ring-size-slider",
            "status-ring-line-width-slider",
            "status-ring-horizontal-offset-slider",
            "status-ring-vertical-offset-slider",
        ).forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag("status-ring-preview").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-draft-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-draft-preview-visual").assertIsDisplayed()
        composeRule.onNodeWithText("1.00×").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("3.0dp").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("恢复默认值").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
    }

    @Test
    fun allRingSlidersUpdateTheLocalPreviewWithoutChangingRuntimeAndResetRestoresDefaults() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-appearance").performClick()
        composeRule.waitForIdle()
        val originalSnapshot = runtime.snapshots.state.value.snapshot
        val originalConnectionGeneration = runtime.snapshots.state.value.connectionGeneration
        val originalOrigin = runtime.realtimeController.currentOrigin()
        val originalActivities = runtime.mutations.activities.value

        composeRule.onNodeWithTag("status-ring-size-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1.20f) }
        composeRule.onNodeWithTag("status-ring-line-width-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(4.5f) }
        composeRule.onNodeWithTag("status-ring-horizontal-offset-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(12f) }
        composeRule.onNodeWithTag("status-ring-vertical-offset-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(-8f) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("status-ring-size-value").performScrollTo().assertTextEquals("1.20×")
        composeRule.onNodeWithTag("status-ring-line-width-value").performScrollTo().assertTextEquals("4.5dp")
        composeRule.onNodeWithTag("status-ring-horizontal-offset-value").performScrollTo().assertTextEquals("12dp")
        composeRule.onNodeWithTag("status-ring-vertical-offset-value").performScrollTo().assertTextEquals("-8dp")
        composeRule.onNodeWithTag("status-ring-draft-preview-visual").assertIsDisplayed()
        val afterPreview = runtime.snapshots.state.value
        assertEquals("Preview must not replace the server session", originalSnapshot?.epoch, afterPreview.snapshot?.epoch)
        assertTrue(
            "An accepted server snapshot may advance during the preview, but must never move backwards",
            afterPreview.snapshot == null || originalSnapshot == null || afterPreview.snapshot.sequence >= originalSnapshot.sequence,
        )
        assertEquals("Preview must not restart the canonical realtime connection", originalConnectionGeneration, afterPreview.connectionGeneration)
        assertEquals("Preview must not change the configured MS origin", originalOrigin, runtime.realtimeController.currentOrigin())
        assertEquals("Preview must not mutate shared activity history", originalActivities, runtime.mutations.activities.value)
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)

        // Leaving and re-entering Settings must retain the persisted local values.
        composeRule.onNodeWithTag("settings-child-back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-root-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-category-appearance").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(2_000) {
            runBlocking {
                val saved = (composeRule.activity.application as MpdServerRemoteApplication)
                    .uiPreferences.statusRingPreferences.first()
                saved == StatusRingPreferences(
                    sizeScale = 1.20f,
                    lineWidthDp = 4.5f,
                    horizontalOffsetDp = 12f,
                    verticalOffsetDp = -8f,
                ).normalized()
            }
        }
        composeRule.onNodeWithTag("status-ring-size-value").performScrollTo().assertTextEquals("1.20×")
        composeRule.onNodeWithTag("status-ring-line-width-value").performScrollTo().assertTextEquals("4.5dp")
        composeRule.onNodeWithTag("status-ring-horizontal-offset-value").performScrollTo().assertTextEquals("12dp")
        composeRule.onNodeWithTag("status-ring-vertical-offset-value").performScrollTo().assertTextEquals("-8dp")

        composeRule.onNodeWithText("恢复默认值").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status-ring-size-value").performScrollTo().assertTextEquals("1.00×")
        composeRule.onNodeWithTag("status-ring-line-width-value").performScrollTo().assertTextEquals("3.0dp")
        composeRule.onNodeWithTag("status-ring-horizontal-offset-value").performScrollTo().assertTextEquals("0dp")
        composeRule.onNodeWithTag("status-ring-vertical-offset-value").performScrollTo().assertTextEquals("0dp")
        composeRule.onNodeWithTag("status-ring-draft-preview-visual").assertIsDisplayed()
        composeRule.waitUntil(2_000) {
            runBlocking {
                (composeRule.activity.application as MpdServerRemoteApplication)
                    .uiPreferences.statusRingPreferences.first() == StatusRingPreferences.DEFAULT
            }
        }
        val afterReset = runtime.snapshots.state.value
        assertEquals("Settings controls must not replace the server session", originalSnapshot?.epoch, afterReset.snapshot?.epoch)
        assertTrue(
            "Live server sequence must not move backwards while changing local settings",
            afterReset.snapshot == null || originalSnapshot == null || afterReset.snapshot.sequence >= originalSnapshot.sequence,
        )
        assertEquals("Settings controls must not restart realtime", originalConnectionGeneration, afterReset.connectionGeneration)
        assertEquals("Settings controls must not change the MS origin", originalOrigin, runtime.realtimeController.currentOrigin())
        assertEquals("Settings controls must never mutate activities", originalActivities, runtime.mutations.activities.value)
    }

    @Test
    fun miniPlayerControlEligibilityFollowsTheCanonicalSnapshotAndBodyOpensFullScreenPlayer() {
        launchNavigation()
        val toggleEnabled = io.github.qyxx2.mpdserver.remote.ui.player.PlayerActions(runtime).let { actions ->
            actions.isEnabled(PlayerAction.PAUSE) || actions.isEnabled(PlayerAction.RESUME)
        }
        val toggle = composeRule.onNodeWithTag("mini-player-play-pause")
        if (toggleEnabled) {
            toggle.assertIsEnabled()
        } else {
            toggle.assertIsNotEnabled().performTouchInput { click() }
            composeRule.waitForIdle()
            assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)
        }

        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
    }

    @Test
    fun statusPageReplacesGlobalActivityEntryAndShowsCanonicalActivityWhenIdle() {
        launchNavigation()
        composeRule.onAllNodesWithTag("activity-details-entry").assertCountEquals(0)
        composeRule.onAllNodesWithTag("status-ring-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("root-nav-settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-category-status").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("status-settings-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-connection").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-freshness").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-empty").assertIsDisplayed()
        composeRule.onAllNodesWithTag("activity-status-sheet").assertCountEquals(0)
        composeRule.onNodeWithTag("settings-child-back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-root-screen").assertIsDisplayed()
    }

    @Test
    fun fullScreenPlayerHidesRootNavigationAndSystemBackRestoresEachRoot() {
        launchNavigation()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val originalRuntime = application.runtime

        AppDestination.rootDestinations.forEach { destination ->
            composeRule.onNodeWithTag("root-nav-${destination.route}").performClick()
            composeRule.waitForIdle()
            assertEquals(destination.route, navController.currentDestination?.route)
            val rootEntry = navController.currentBackStackEntry

            composeRule.runOnUiThread {
                navController.navigate(AppDestination.FULL_SCREEN_PLAYER.route)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
            composeRule.onNodeWithTag("full-screen-exit-gesture-band").assertIsDisplayed()
            composeRule.onNodeWithTag("player-progress-track").assertIsDisplayed()
            composeRule.onNodeWithTag("player-source-format").assertIsDisplayed()
            composeRule.onNodeWithTag("player-playback-controls").assertIsDisplayed()
            val playbackActions = PlayerActions(originalRuntime)
            if (playbackActions.isEnabled(PlayerAction.PAUSE) || playbackActions.isEnabled(PlayerAction.RESUME)) {
                composeRule.onNodeWithTag("player-play-pause").assertIsEnabled()
            } else {
                composeRule.onNodeWithTag("player-play-pause").assertIsNotEnabled()
            }
            composeRule.onNodeWithTag("player-bottom-now-playing").assertIsSelected()
            composeRule.onNodeWithTag("player-bottom-queue").assertIsDisplayed()
            composeRule.onAllNodesWithTag("full-screen-top-back").assertCountEquals(0)
            composeRule.onAllNodesWithTag("full-screen-top-queue-toggle").assertCountEquals(0)
            composeRule.onAllNodesWithText("专辑封面").assertCountEquals(0)
            composeRule.onAllNodesWithText("暂无歌词").assertCountEquals(0)
            composeRule.onAllNodesWithText("00:00").assertCountEquals(0)
            composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
            composeRule.onAllNodesWithTag("app-bar").assertCountEquals(0)
            composeRule.onAllNodesWithTag("root-content").assertCountEquals(0)
            composeRule.onAllNodesWithTag("root-navigation-bar").assertCountEquals(0)

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
            assertEquals(destination.route, navController.currentDestination?.route)
            assertSame(rootEntry, navController.currentBackStackEntry)
            composeRule.onNodeWithTag("root-nav-${destination.route}").assertIsSelected()
            assertSame(originalRuntime, application.runtime)
        }
    }
}
