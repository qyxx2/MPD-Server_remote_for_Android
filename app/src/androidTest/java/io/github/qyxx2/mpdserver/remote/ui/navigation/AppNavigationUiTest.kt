package io.github.qyxx2.mpdserver.remote.ui.navigation

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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerAction
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerActions
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
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

    private fun launchNavigation() {
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
    fun settingsCanSelectAndPersistAllThemePreferences() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
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
        composeRule.onNodeWithTag("activity-details-entry").assertIsDisplayed()
        composeRule.onAllNodesWithTag("status-ring-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("root-content").assertIsDisplayed()
        assertTrue(
            "Root placeholder content must expose scrolling semantics",
            composeRule.onNodeWithTag("root-placeholder-content").fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
        )
        composeRule.onNodeWithTag("root-navigation-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-body").assertIsDisplayed()
        assertMiniPlayerControlMatchesCanonicalState()

        val contentBounds = composeRule.onNodeWithTag("root-content").fetchSemanticsNode().boundsInRoot
        val miniBounds = composeRule.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        val navigationBounds = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Root content must end above the mini-player", contentBounds.bottom <= miniBounds.top + 1f)
        assertTrue("Mini-player must end above the fixed navigation", miniBounds.bottom <= navigationBounds.top + 1f)

        val detailsBounds = composeRule.onNodeWithTag("activity-details-entry").fetchSemanticsNode().boundsInRoot
        val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("Independent activity details entry must preserve a 48dp touch target", detailsBounds.width >= minimumTouchTargetPx)
        assertTrue("Independent activity details entry must preserve a 48dp touch target", detailsBounds.height >= minimumTouchTargetPx)
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
            if (destination == AppDestination.SETTINGS) {
                composeRule.onNodeWithTag("status-ring-settings-content").assertIsDisplayed()
                listOf(
                    "status-ring-size-slider",
                    "status-ring-line-width-slider",
                    "status-ring-horizontal-offset-slider",
                    "status-ring-vertical-offset-slider",
                ).forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }
                composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
            } else {
                assertTrue(
                    "Root placeholder content must expose scrolling semantics",
                    composeRule.onNodeWithTag("root-placeholder-content").fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
                )
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
            composeRule.onNodeWithTag("server-origin-settings-title").assertIsDisplayed()
            composeRule.onNodeWithTag("server-origin-input").performScrollTo()
            val serverOriginField = composeRule.onNodeWithTag("server-origin-input")
            serverOriginField.performTextClearance()
            serverOriginField.performTextInput("ms.example.com/api")
            composeRule.onNodeWithTag("server-origin-save").performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("server-origin-supporting-text", useUnmergedTree = true)
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

        composeRule.onNodeWithText("状态环").assertIsDisplayed()
        listOf(
            "status-ring-size-slider",
            "status-ring-line-width-slider",
            "status-ring-horizontal-offset-slider",
            "status-ring-vertical-offset-slider",
        ).forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag("status-ring-preview").performScrollTo().assertIsDisplayed()
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
        val originalSnapshot = runtime.snapshots.state.value
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
        composeRule.onNodeWithContentDescription(
            "状态环预览；尺寸 1.20×；线宽 4.5dp；水平偏移 12dp；垂直偏移 -8dp",
        ).performScrollTo().assertIsDisplayed()
        assertEquals("Preview must not mutate the canonical snapshot", originalSnapshot, runtime.snapshots.state.value)
        assertEquals("Preview must not mutate shared activity history", originalActivities, runtime.mutations.activities.value)
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)

        // Leaving and re-entering Settings must retain the persisted local values.
        composeRule.onNodeWithTag("root-nav-${AppDestination.HOME.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
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
        composeRule.onNodeWithContentDescription(
            "状态环预览；尺寸 1.00×；线宽 3.0dp；水平偏移 0dp；垂直偏移 0dp",
        ).performScrollTo().assertIsDisplayed()
        assertEquals("Settings controls must never mutate the snapshot", originalSnapshot, runtime.snapshots.state.value)
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
    fun independentActivityEntryOpensReadableEmptySheetWhileRingRemainsHiddenWhenIdle() {
        launchNavigation()
        composeRule.onNodeWithTag("activity-details-entry").assertIsDisplayed()
        composeRule.onAllNodesWithTag("status-ring-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("activity-details-entry").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("连接与活动").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-summary").assertIsDisplayed()
        // Connectivity/freshness labels are projections of the configured canonical Runtime.
        // This test verifies the sheet and empty activity state without assuming localhost/offline.
        composeRule.onNodeWithTag("activity-status-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-close").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("activity-status-sheet").assertCountEquals(0)
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
