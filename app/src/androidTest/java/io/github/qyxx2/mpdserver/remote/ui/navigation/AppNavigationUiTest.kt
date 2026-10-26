package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var runtime: AppRuntime

    private fun launchNavigation() {
        runtime = (composeRule.activity.application as MpdServerRemoteApplication).runtime
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            AppNavHost(runtime = runtime, navController = controller)
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
        composeRule.onNodeWithTag("global-status-entry").assertIsDisplayed()
        composeRule.onNodeWithTag("root-content").assertIsDisplayed()
        assertTrue(
            "Root placeholder content must expose scrolling semantics",
            composeRule.onNodeWithTag("root-placeholder-content").fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
        )
        composeRule.onNodeWithTag("root-navigation-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.onNodeWithText("暂无播放曲目").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-play-pause").assertIsNotEnabled()

        val contentBounds = composeRule.onNodeWithTag("root-content").fetchSemanticsNode().boundsInRoot
        val miniBounds = composeRule.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        val navigationBounds = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Root content must end above the mini-player", contentBounds.bottom <= miniBounds.top + 1f)
        assertTrue("Mini-player must end above the fixed navigation", miniBounds.bottom <= navigationBounds.top + 1f)

        val statusBounds = composeRule.onNodeWithTag("global-status-entry").fetchSemanticsNode().boundsInRoot
        val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("Global status entry must preserve a 48dp touch target", statusBounds.width >= minimumTouchTargetPx)
        assertTrue("Global status entry must preserve a 48dp touch target", statusBounds.height >= minimumTouchTargetPx)
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
            assertTrue(
                "Root placeholder content must expose scrolling semantics",
                composeRule.onNodeWithTag("root-placeholder-content").fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
            )
            composeRule.onNodeWithTag("root-navigation-bar").assertIsDisplayed()
            if (destination == AppDestination.SETTINGS) {
                composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
            } else {
                composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
                composeRule.onNodeWithTag("mini-player-play-pause").assertIsNotEnabled()
            }
            val navBounds = composeRule.onNodeWithTag("root-nav-${destination.route}").fetchSemanticsNode().boundsInRoot
            val minTarget = with(composeRule.density) { 48.dp.toPx() }
            assertTrue("Root navigation items must retain 48dp touch targets", navBounds.height >= minTarget)
            assertEquals(destination.route, navController.currentDestination?.route)
        }
    }

    @Test
    fun miniPlayerActionIsDisabledWhileBodyOpensFullScreenPlayer() {
        launchNavigation()
        composeRule.onNodeWithTag("mini-player-play-pause").assertIsNotEnabled().performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)

        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithText("全屏播放器占位").assertIsDisplayed()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
    }

    @Test
    fun globalStatusEntryOpensReadableEmptyActivitySheetAndCanCloseIt() {
        launchNavigation()
        composeRule.onNodeWithTag("global-status-entry").assertIsDisplayed()
        composeRule.onNodeWithTag("global-status-entry").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("连接与活动").assertIsDisplayed()
        composeRule.onNodeWithTag("activity-status-summary").assertIsDisplayed()
        composeRule.onNodeWithText("当前状态：未连接").assertIsDisplayed()
        composeRule.onNodeWithText("连接：未连接").assertIsDisplayed()
        composeRule.onNodeWithText("快照：未确认").assertIsDisplayed()
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
            composeRule.onNodeWithText("全屏播放器占位").assertIsDisplayed()
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
