package io.github.qyxx2.mpdserver.remote.ui.navigation

import android.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
