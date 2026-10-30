package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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

    private fun openPlayerAndWaitForArtwork() {
        composeRule.onNodeWithTag("mini-player-body").performClick()
        val minimumTouchPx = with(composeRule.density) { 48.dp.toPx() }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val playerVisible = composeRule.onAllNodesWithTag("full-screen-player-root")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            val outputNode = composeRule.onAllNodesWithTag("player-bottom-output")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
            val motionNode = composeRule.onAllNodesWithTag("full-screen-player-motion-layer")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route &&
                playerVisible &&
                outputNode != null && outputNode.boundsInRoot.height >= minimumTouchPx &&
                motionNode != null && kotlin.math.abs(motionNode.boundsInRoot.top) <= 1.5f
        }
        composeRule.waitForIdle()
    }

    @Test
    fun playerAndQueueAreSecondaryFullScreenDestinationsWithSharedRuntime() {
        launchNavigation()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val runtime = application.runtime

        openPlayerAndWaitForArtwork()
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
        val playbackActions = PlayerActions(runtime)
        if (playbackActions.isEnabled(PlayerAction.PAUSE) || playbackActions.isEnabled(PlayerAction.RESUME)) {
            composeRule.onNodeWithTag("player-play-pause").assertIsEnabled()
        } else {
            composeRule.onNodeWithTag("player-play-pause").assertIsNotEnabled()
        }
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.QUEUE.route &&
                composeRule.onAllNodesWithTag("queue-screen")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.QUEUE.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("queue-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("player-bottom-queue").assertIsSelected()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
        assertSame(runtime, application.runtime)

        composeRule.onNodeWithTag("player-bottom-now-playing").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route &&
                composeRule.onAllNodesWithTag("full-screen-player-root")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
        assertSame(runtime, application.runtime)

        // Re-enter after Queue was popped: a new destination must be available and visible.
        composeRule.onNodeWithTag("player-bottom-queue").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.QUEUE.route &&
                composeRule.onAllNodesWithTag("queue-screen")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeRule.onNodeWithTag("queue-screen").assertIsDisplayed()
        assertSame(runtime, application.runtime)
    }

    @Test
    fun horizontalSwipesSwitchBetweenPlayerAndQueueRoutes() {
        launchNavigation()
        openPlayerAndWaitForArtwork()
        composeRule.onNodeWithTag("full-screen-player-motion-layer").assertIsDisplayed()

        composeRule.onNodeWithTag("full-screen-player-motion-layer").performTouchInput {
            swipeLeft()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.QUEUE.route
        }
        composeRule.onNodeWithTag("queue-screen").assertIsDisplayed()

        composeRule.onNodeWithTag("full-screen-player-motion-layer").performTouchInput {
            swipeRight()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route
        }
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
    }

    @Test
    fun downwardSwipeStartingOnArtworkDismissesPlayerToPreviousRoot() {
        launchNavigation()
        openPlayerAndWaitForArtwork()
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
    fun artworkTapOpensLyricsAndShortDownwardDragOnArtworkDoesNotDismiss() {
        launchNavigation()
        openPlayerAndWaitForArtwork()

        // Artwork tap is the lyrics-mode toggle, but it must not dismiss the full-screen Player.
        composeRule.onNodeWithTag("player-artwork-stage").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.FULL_SCREEN_PLAYER.route, navController.currentDestination?.route)
        composeRule.onNodeWithTag("lyrics-stage").assertIsDisplayed()

        composeRule.onNodeWithTag("lyrics-return-artwork").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()

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
        openPlayerAndWaitForArtwork()

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
    fun shortDownwardSwipeOnQueueTopBandReturnsWithoutLeavingQueue() {
        launchNavigation()
        openPlayerAndWaitForArtwork()
        composeRule.onNodeWithTag("player-bottom-queue").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.QUEUE.route
        }

        val shortDistancePx = with(composeRule.density) { 48.dp.toPx() }
        composeRule.onNodeWithTag("full-screen-exit-gesture-band").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + shortDistancePx), 160L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.QUEUE.route, navController.currentDestination?.route)
        val motionBounds = composeRule.onNodeWithTag("full-screen-player-motion-layer")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("the shared dismissal motion must settle back to its origin", 0f, motionBounds.top, 1.5f)
    }

    @Test
    fun miniPlayerPullUpTracksFingerAndReturnsWhenReleasedBeforeThreshold() {
        launchNavigation()
        val miniBounds = composeRule.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        val navBefore = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot
        val windowHeightPx = composeRule.activity.window.decorView.height.toFloat()
        val dragDistancePx = windowHeightPx * 0.30f

        composeRule.onNodeWithTag("mini-player-body").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - dragDistancePx), 800L)
        }
        composeRule.waitForIdle()

        assertEquals("pre-threshold drag keeps the root route mounted", AppDestination.HOME.route, navController.currentDestination?.route)
        val preview = composeRule.onAllNodesWithTag("mini-player-expansion-preview")
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue("a full-player surface must be pulled out while the finger is held", preview.isNotEmpty())
        val playerBounds = composeRule.onNodeWithTag("mini-player-expansion-motion-layer")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the Player top edge must follow the mini-player upward instead of scaling open: $miniBounds -> $playerBounds",
            playerBounds.top < miniBounds.top - dragDistancePx * 0.65f,
        )
        val navDuringDrag = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the bottom navigation must move down as the Player is pulled out: $navBefore -> $navDuringDrag",
            navDuringDrag.top > navBefore.top,
        )

        composeRule.onNodeWithTag("mini-player-body").performTouchInput { up() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            navController.currentDestination?.route == AppDestination.HOME.route &&
                composeRule.onAllNodesWithTag("mini-player-expansion-preview")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        val navAfter = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot
        assertEquals("early release restores navigation to its original position", navBefore.top, navAfter.top, 2f)
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
    }

    @Test
    fun fastUpwardFlickEntersFullScreenBeforeHalfWindowDistanceWhileFingerIsHeld() {
        launchNavigation()
        val windowHeightPx = composeRule.activity.window.decorView.height.toFloat()
        val dragDistancePx = windowHeightPx * 0.28f

        composeRule.onNodeWithTag("mini-player-body").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - dragDistancePx * 0.45f), 40L)
            moveTo(Offset(center.x, center.y - dragDistancePx), 40L)
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route
        }
        assertTrue(
            "the fast-flick path must trigger before the 50% distance and before finger release",
            dragDistancePx < windowHeightPx * 0.5f,
        )
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
        composeRule.onAllNodesWithTag("root-navigation-bar").assertCountEquals(0)

        composeRule.waitForIdle()
    }

    @Test
    fun downwardDismissNavigationBarUsesNormalEnabledColors() {
        launchNavigation()
        val normalPixels = composeRule.onNodeWithTag("root-nav-home")
            .captureToImage().toPixelMap()
        openPlayerAndWaitForArtwork()

        val windowHeightPx = composeRule.activity.window.decorView.height.toFloat()
        val gesture = composeRule.onNodeWithTag("full-screen-exit-gesture-band")
        gesture.performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + windowHeightPx * 0.99f), 320L)
        }
        composeRule.waitForIdle()

        val transitionPixels = composeRule.onNodeWithTag("root-nav-home")
            .captureToImage().toPixelMap()
        assertEquals(normalPixels.width, transitionPixels.width)
        assertEquals(normalPixels.height, transitionPixels.height)
        var differingPixels = 0
        for (x in 0 until normalPixels.width) {
            for (y in 0 until normalPixels.height) {
                if (normalPixels[x, y] != transitionPixels[x, y]) differingPixels++
            }
        }
        assertEquals(
            "the moving navigation item must keep the same icon/text colors as its enabled root copy",
            0,
            differingPixels,
        )
        gesture.performTouchInput { up() }
    }

    @Test
    fun downwardDismissRevealsNavigationBarFromBottomInSyncWithPlayer() {
        launchNavigation()
        val navigationBefore = composeRule.onNodeWithTag("root-navigation-bar")
            .fetchSemanticsNode().boundsInRoot
        openPlayerAndWaitForArtwork()
        val windowHeightPx = composeRule.activity.window.decorView.height.toFloat()
        val gesture = composeRule.onNodeWithTag("full-screen-exit-gesture-band")

        // A short dismissal must pull the navigation bar up with the player, then return it
        // below the window along with the player's spring-back.
        gesture.performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + windowHeightPx * 0.10f), 240L)
        }
        composeRule.waitForIdle()
        val navigationDuringCancelledDrag = composeRule.onNodeWithTag("root-navigation-bar")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the navigation bar must move up from below the window during a short downward drag",
            navigationDuringCancelledDrag.top > navigationBefore.top,
        )
        gesture.performTouchInput { up() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route &&
                composeRule.onAllNodesWithTag("root-navigation-bar")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }

        gesture.performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + windowHeightPx * 0.12f), 240L)
        }
        composeRule.waitForIdle()
        val navigationAtFirstDrag = composeRule.onNodeWithTag("root-navigation-bar")
            .fetchSemanticsNode().boundsInRoot

        gesture.performTouchInput {
            moveTo(Offset(center.x, center.y + windowHeightPx * 0.30f), 390L)
        }
        composeRule.waitForIdle()
        val navigationAtSecondDrag = composeRule.onNodeWithTag("root-navigation-bar")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the navigation bar must begin below its resting position as the player moves down: before=$navigationBefore first=$navigationAtFirstDrag",
            navigationAtFirstDrag.top > navigationBefore.top,
        )
        assertTrue(
            "the navigation bar must rise as the player continues down: first=$navigationAtFirstDrag second=$navigationAtSecondDrag",
            navigationAtSecondDrag.top < navigationAtFirstDrag.top,
        )

        gesture.performTouchInput { up() }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            navController.currentDestination?.route == AppDestination.HOME.route &&
                composeRule.onAllNodesWithTag("root-navigation-bar")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        val navigationAfter = composeRule.onNodeWithTag("root-navigation-bar")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("the completed transition must end at the root navigation resting position", navigationBefore.top, navigationAfter.top, 2f)
    }

    @Test
    fun miniPlayerPullUpCrossingThresholdContinuesIntoFullScreenPlayer() {
        launchNavigation()
        val windowHeightPx = composeRule.activity.window.decorView.height.toFloat()
        val thresholdPx = windowHeightPx * 0.50f
        val dragDistancePx = thresholdPx + with(composeRule.density) { 24.dp.toPx() }

        composeRule.onNodeWithTag("mini-player-body").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - dragDistancePx), 900L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val playerVisible = composeRule.onAllNodesWithTag("full-screen-player-root")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            val motionBounds = composeRule.onAllNodesWithTag("full-screen-player-motion-layer")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()?.boundsInRoot
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route &&
                playerVisible && motionBounds != null && kotlin.math.abs(motionBounds.top) <= 1.5f
        }
        composeRule.waitForIdle()

        val motionBounds = composeRule.onNodeWithTag("full-screen-player-motion-layer")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("threshold handoff finishes at the real window top without a scale-open phase", 0f, motionBounds.top, 2f)
        composeRule.onNodeWithTag("full-screen-player-root").assertIsDisplayed()
        composeRule.onAllNodesWithTag("root-navigation-bar").assertCountEquals(0)
    }

    @Test
    fun downwardSwipeFromQueueExitsToThePreviousRootInsteadOfStoppingOnPlayer() {
        launchNavigation()
        openPlayerAndWaitForArtwork()
        composeRule.onNodeWithTag("player-bottom-queue").performClick()
        composeRule.waitForIdle()
        assertEquals(AppDestination.QUEUE.route, navController.currentDestination?.route)

        val swipeDistancePx = with(composeRule.density) { 280.dp.toPx() }
        composeRule.onNodeWithTag("full-screen-exit-gesture-band").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + swipeDistancePx), 180L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)
    }
}
