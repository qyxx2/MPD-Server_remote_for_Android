package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AppNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var runtime: AppRuntime

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
        composeRule.onNodeWithText("暂无播放曲目").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-play-pause").assertIsNotEnabled()

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
                composeRule.onNodeWithTag("status-ring-size-slider").assertIsDisplayed()
                composeRule.onNodeWithTag("status-ring-line-width-slider").assertIsDisplayed()
                composeRule.onNodeWithTag("status-ring-horizontal-offset-slider").assertIsDisplayed()
                composeRule.onNodeWithTag("status-ring-vertical-offset-slider").assertIsDisplayed()
                composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
            } else {
                assertTrue(
                    "Root placeholder content must expose scrolling semantics",
                    composeRule.onNodeWithTag("root-placeholder-content").fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy),
                )
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
    fun settingsShowsFourRingControlsPreviewAndNoMiniPlayer() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("状态环").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-size-slider").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-line-width-slider").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-horizontal-offset-slider").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-vertical-offset-slider").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-preview").assertIsDisplayed()
        composeRule.onNodeWithText("1.00×").assertIsDisplayed()
        composeRule.onNodeWithText("3.0dp").assertIsDisplayed()
        composeRule.onNodeWithText("恢复默认值").assertIsDisplayed()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
    }

    @Test
    fun allRingSlidersUpdateTheLocalPreviewWithoutChangingRuntimeAndResetRestoresDefaults() {
        launchNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
        val originalSnapshot = runtime.snapshots.state.value
        val originalActivities = runtime.mutations.activities.value

        composeRule.onNodeWithTag("status-ring-size-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1.20f) }
        composeRule.onNodeWithTag("status-ring-line-width-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(4.5f) }
        composeRule.onNodeWithTag("status-ring-horizontal-offset-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(12f) }
        composeRule.onNodeWithTag("status-ring-vertical-offset-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(-8f) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("status-ring-size-value").assertTextEquals("1.20×")
        composeRule.onNodeWithTag("status-ring-line-width-value").assertTextEquals("4.5dp")
        composeRule.onNodeWithTag("status-ring-horizontal-offset-value").assertTextEquals("12dp")
        composeRule.onNodeWithTag("status-ring-vertical-offset-value").assertTextEquals("-8dp")
        composeRule.onNodeWithContentDescription(
            "状态环预览；尺寸 1.20×；线宽 4.5dp；水平偏移 12dp；垂直偏移 -8dp",
        ).assertIsDisplayed()
        assertEquals("Preview must not mutate the canonical snapshot", originalSnapshot, runtime.snapshots.state.value)
        assertEquals("Preview must not mutate shared activity history", originalActivities, runtime.mutations.activities.value)
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)

        // Leaving and re-entering Settings must retain the persisted local values.
        composeRule.onNodeWithTag("root-nav-${AppDestination.HOME.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status-ring-size-value").assertTextEquals("1.20×")
        composeRule.onNodeWithTag("status-ring-line-width-value").assertTextEquals("4.5dp")
        composeRule.onNodeWithTag("status-ring-horizontal-offset-value").assertTextEquals("12dp")
        composeRule.onNodeWithTag("status-ring-vertical-offset-value").assertTextEquals("-8dp")

        composeRule.onNodeWithText("恢复默认值").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status-ring-size-value").assertTextEquals("1.00×")
        composeRule.onNodeWithTag("status-ring-line-width-value").assertTextEquals("3.0dp")
        composeRule.onNodeWithTag("status-ring-horizontal-offset-value").assertTextEquals("0dp")
        composeRule.onNodeWithTag("status-ring-vertical-offset-value").assertTextEquals("0dp")
        composeRule.onNodeWithContentDescription(
            "状态环预览；尺寸 1.00×；线宽 3.0dp；水平偏移 0dp；垂直偏移 0dp",
        ).assertIsDisplayed()
        assertEquals("Settings controls must never mutate the snapshot", originalSnapshot, runtime.snapshots.state.value)
        assertEquals("Settings controls must never mutate activities", originalActivities, runtime.mutations.activities.value)
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
    fun independentActivityEntryOpensReadableEmptySheetWhileRingRemainsHiddenWhenIdle() {
        launchNavigation()
        composeRule.onNodeWithTag("activity-details-entry").assertIsDisplayed()
        composeRule.onAllNodesWithTag("status-ring-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("activity-details-entry").performClick()
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
            composeRule.onNodeWithTag("full-screen-player-placeholder").assertIsDisplayed()
            composeRule.onNodeWithText("全屏播放器占位").assertIsDisplayed()
            composeRule.onNodeWithText("播放器界面将在后续阶段接入。").assertIsDisplayed()
            composeRule.onAllNodesWithText("专辑封面").assertCountEquals(0)
            composeRule.onAllNodesWithText("暂无歌词").assertCountEquals(0)
            composeRule.onAllNodesWithText("播放队列").assertCountEquals(0)
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
