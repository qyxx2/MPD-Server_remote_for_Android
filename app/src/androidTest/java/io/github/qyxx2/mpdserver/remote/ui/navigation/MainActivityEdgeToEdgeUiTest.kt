package io.github.qyxx2.mpdserver.remote.ui.navigation

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MainActivityEdgeToEdgeUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun transparentSystemBarsAndSafeContentInsetsAreAppliedByTheRealActivity() {
        composeRule.waitForIdle()
        val activity = composeRule.activity
        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)

        val rootInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        assertNotNull("The real Activity must receive window Insets", rootInsets)
        val safeInsets = requireNotNull(rootInsets).getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val appBarTop = composeRule.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot.top
        val navigationBottom = composeRule.onNodeWithTag("root-navigation-bar").fetchSemanticsNode().boundsInRoot.bottom
        val rootHeight = activity.window.decorView.height.toFloat()
        val density = composeRule.density.density
        val tolerancePx = 2f * density

        assertTrue(
            "App content must start at or below the reported status-bar/cutout safe inset",
            appBarTop + tolerancePx >= safeInsets.top,
        )
        assertTrue(
            "Root navigation must remain above the reported bottom system-bar inset",
            navigationBottom <= rootHeight - safeInsets.bottom + tolerancePx,
        )
        assertTrue(
            "The content inset must not be doubled beyond one system-bar inset",
            abs(appBarTop - safeInsets.top) <= tolerancePx,
        )
    }

    @Test
    fun fullScreenPlayerMotionCanvasExtendsBehindSystemBarsWhileGestureBandStaysSafe() {
        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()

        val activity = composeRule.activity
        val rootInsets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
        val safeInsets = rootInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val motionBounds = composeRule.onNodeWithTag("full-screen-player-motion-layer")
            .fetchSemanticsNode().boundsInRoot
        val artworkBackdropBounds = composeRule.onNodeWithTag("player-artwork-backdrop")
            .fetchSemanticsNode().boundsInRoot
        val gestureBandBounds = composeRule.onNodeWithTag("full-screen-exit-gesture-band")
            .fetchSemanticsNode().boundsInRoot
        val windowHeight = activity.window.decorView.height.toFloat()
        val tolerancePx = 2f * composeRule.density.density

        assertTrue(
            "The animated player layer must start at the window top, not below the status-bar inset: $motionBounds",
            abs(motionBounds.top) <= tolerancePx,
        )
        assertTrue(
            "The animated player layer must extend through the bottom system-bar area: $motionBounds windowHeight=$windowHeight",
            motionBounds.bottom >= windowHeight - tolerancePx,
        )
        assertTrue(
            "The artwork backdrop must be rendered behind the transparent status bar: $artworkBackdropBounds",
            abs(artworkBackdropBounds.top) <= tolerancePx,
        )
        assertTrue(
            "The dismiss gesture must begin below the actual status-bar/cutout inset: band=$gestureBandBounds safeTop=${safeInsets.top}",
            abs(gestureBandBounds.top - safeInsets.top) <= tolerancePx,
        )

        // Hold a short drag before release and verify the whole window-sized layer moves, not just
        // the content below the status bar. The release remains under the dismissal threshold.
        val gestureBand = composeRule.onNodeWithTag("full-screen-exit-gesture-band")
        gestureBand.performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + 64f), 120L)
        }
        composeRule.waitForIdle()
        val draggedMotionBounds = composeRule.onNodeWithTag("full-screen-player-motion-layer")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The window-sized player canvas including status-bar area must follow the active drag: $draggedMotionBounds",
            draggedMotionBounds.top > tolerancePx,
        )
        gestureBand.performTouchInput { up() }
        composeRule.waitForIdle()
        val restoredMotionBounds = composeRule.onNodeWithTag("full-screen-player-motion-layer")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "A short drag must restore the entire player layer to the window top: $restoredMotionBounds",
            abs(restoredMotionBounds.top) <= tolerancePx,
        )
    }
}
