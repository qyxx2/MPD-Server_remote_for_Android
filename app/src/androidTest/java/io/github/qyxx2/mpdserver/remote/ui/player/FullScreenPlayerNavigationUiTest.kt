package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppDestination
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullScreenPlayerNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private fun launchNavigation() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            AppNavHost(
                runtime = application.runtime,
                navController = controller,
                uiPreferences = application.uiPreferences,
                preferenceWriteScope = application.uiPreferencesWriteScope,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun playerAndQueueAreSecondaryFullScreenDestinationsWithSharedRuntime() {
        launchNavigation()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val runtime = application.runtime

        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-backdrop-theme-mask").assertIsDisplayed()
        val backdropBounds = composeRule.onNodeWithTag("full-screen-root-backdrop").fetchSemanticsNode().boundsInRoot
        assertTrue("root backdrop should remain laid out beneath the player", backdropBounds.width > 0f && backdropBounds.height > 0f)
        composeRule.onNodeWithTag("full-screen-exit-gesture-band").assertIsDisplayed()
        composeRule.onNodeWithTag("player-bottom-now-playing").assertIsSelected()
        composeRule.onNodeWithTag("player-progress-track").assertIsDisplayed()
        composeRule.onNodeWithTag("player-source-format").assertIsDisplayed()
        composeRule.onNodeWithTag("player-play-pause").assertIsNotEnabled()
        val minimumTouchPx = with(composeRule.density) { 48.dp.toPx() }
        listOf("player-bottom-output", "player-bottom-now-playing", "player-bottom-queue").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must provide at least a 48dp touch target", bounds.width >= minimumTouchPx)
            assertTrue("$tag must provide at least a 48dp touch target", bounds.height >= minimumTouchPx)
        }
        composeRule.onAllNodesWithTag("app-bar").assertCountEquals(0)
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
        composeRule.onAllNodesWithTag("root-navigation-bar").assertCountEquals(0)

        composeRule.onNodeWithTag("player-bottom-queue").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.QUEUE.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("queue-destination-placeholder").assertIsDisplayed()
        composeRule.onNodeWithTag("player-bottom-queue").assertIsSelected()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
        assertSame(runtime, application.runtime)

        composeRule.onNodeWithTag("player-bottom-now-playing").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
        assertSame(runtime, application.runtime)
    }

    @Test
    fun downwardSwipeStartingOnArtworkDismissesPlayerToPreviousRoot() {
        launchNavigation()
        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()

        val swipeDistancePx = with(composeRule.density) { 280.dp.toPx() }
        composeRule.onNodeWithTag("player-artwork-stage").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + swipeDistancePx), 180L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)
    }

    @Test
    fun aShortDownwardDragOnArtworkRestoresThePlayerInsteadOfDismissing() {
        launchNavigation()
        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()

        // A plain press/release on artwork must remain a tap, not a dismiss gesture.
        composeRule.onNodeWithTag("player-artwork-stage").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)

        val shortDistancePx = with(composeRule.density) { 48.dp.toPx() }
        composeRule.onNodeWithTag("player-artwork-stage").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + shortDistancePx), 180L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
    }

    @Test
    fun downwardSwipeFromAppContentTopBandExitsToThePreviousRoot() {
        launchNavigation()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val runtime = application.runtime
        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()

        val swipeDistancePx = with(composeRule.density) { 280.dp.toPx() }
        composeRule.onNodeWithTag("full-screen-exit-gesture-band").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + swipeDistancePx), 180L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)
        assertSame(runtime, application.runtime)
    }

    @Test
    fun downwardSwipeFromQueueExitsToThePreviousRootInsteadOfStoppingOnPlayer() {
        launchNavigation()
        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-bottom-queue").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.QUEUE.route, navController.currentDestination?.route)

        val swipeDistancePx = with(composeRule.density) { 128.dp.toPx() }
        composeRule.onNodeWithTag("full-screen-exit-gesture-band").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + swipeDistancePx), 180L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)
    }
}
