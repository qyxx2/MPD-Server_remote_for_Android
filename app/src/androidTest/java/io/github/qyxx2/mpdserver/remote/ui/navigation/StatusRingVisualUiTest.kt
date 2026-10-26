package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityVisualState
import io.github.qyxx2.mpdserver.remote.ui.components.GlobalStatusRingOverlay
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingAnchorSource
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingInsets
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingPlacementCalculator
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingPlacementSettings
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingRect
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class StatusRingVisualUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun realWindowCutoutGeometryPositionsRingAndVisualStatesRemainAccessible() {
        val activity = composeRule.activity
        composeRule.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.statusBarColor = AndroidColor.TRANSPARENT
            activity.window.navigationBarColor = AndroidColor.TRANSPARENT
            ViewCompat.requestApplyInsets(activity.window.decorView)
        }
        composeRule.waitUntil(5_000L) { ViewCompat.getRootWindowInsets(activity.window.decorView) != null }
        val windowInsets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
        val safe = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val geometry = StatusRingWindowGeometry(
            cutoutBounds = windowInsets.displayCutout?.boundingRects.orEmpty().map {
                StatusRingRect(it.left, it.top, it.right, it.bottom)
            },
            safeInsets = StatusRingInsets(safe.left, safe.top, safe.right, safe.bottom),
        )
        val density = composeRule.density.density
        var currentPresentation by mutableStateOf(makePresentation(ActivityVisualState.PENDING, "正在处理"))

        composeRule.setContent {
            GlobalStatusRingOverlay(
                presentation = currentPresentation,
                geometry = geometry,
                preferences = StatusRingPreferences.DEFAULT,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composeRule.onNodeWithTag("status-ring-overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-visual").assertIsDisplayed()
        composeRule.onNodeWithTag("status-ring-graphic").assertIsDisplayed()

        val rootBounds = composeRule.onNodeWithTag("status-ring-overlay").fetchSemanticsNode().boundsInRoot
        // Compose test bounds are already in window pixels; only dp-based settings need density conversion.
        val windowWidthPx = rootBounds.width.roundToInt()
        val windowHeightPx = rootBounds.height.roundToInt()
        val expected = StatusRingPlacementCalculator.calculate(
            screenWidthPx = windowWidthPx,
            screenHeightPx = windowHeightPx,
            safeInsets = geometry.safeInsets,
            cutoutBounds = geometry.cutoutBounds,
            density = density,
            settings = StatusRingPlacementSettings(
                sizeScale = StatusRingPreferences.SIZE_SCALE_DEFAULT,
                horizontalOffsetDp = StatusRingPreferences.POSITION_OFFSET_DEFAULT_DP,
                verticalOffsetDp = StatusRingPreferences.POSITION_OFFSET_DEFAULT_DP,
            ),
        )
        val ringBounds = composeRule.onNodeWithTag("status-ring-visual").fetchSemanticsNode().boundsInRoot
        val radius = expected.diameterPx / 2f
        val expectedVisibleLeft = (expected.centerXpx - radius).coerceAtLeast(0f)
        val expectedVisibleTop = (expected.centerYpx - radius).coerceAtLeast(0f)
        val expectedVisibleRight = (expected.centerXpx + radius).coerceAtMost(windowWidthPx.toFloat())
        val expectedVisibleBottom = (expected.centerYpx + radius).coerceAtMost(windowHeightPx.toFloat())
        assertTrue("Rendered ring's clipped left edge must match placement", abs(ringBounds.left - expectedVisibleLeft) <= 2f)
        assertTrue("Rendered ring's clipped top edge must match placement", abs(ringBounds.top - expectedVisibleTop) <= 2f)
        assertTrue("Rendered ring's clipped right edge must match placement", abs(ringBounds.right - expectedVisibleRight) <= 2f)
        assertTrue("Rendered ring's clipped bottom edge must match placement", abs(ringBounds.bottom - expectedVisibleBottom) <= 2f)

        val visualCases = listOf(
            ActivityVisualState.UNKNOWN to "结果未知",
            ActivityVisualState.FAILED to "操作失败",
            ActivityVisualState.PENDING to "正在处理",
            ActivityVisualState.SYNCING to "正在同步",
            ActivityVisualState.DISCONNECTED to "未连接",
            ActivityVisualState.CONNECTING to "正在连接",
            ActivityVisualState.STALE to "状态已过期",
            ActivityVisualState.SUCCESS to "操作已确认",
            ActivityVisualState.INFO to "提示",
        )
        visualCases.forEach { (state, label) ->
            composeRule.runOnUiThread { currentPresentation = makePresentation(state, label) }
            composeRule.onNodeWithContentDescription(
                "状态环：$label；定位方式：${if (expected.anchorSource == StatusRingAnchorSource.DISPLAY_CUTOUT) "系统挖孔几何" else "手动校准回退"}",
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("status-ring-graphic").assertIsDisplayed()
        }
    }

    private fun makePresentation(state: ActivityVisualState, label: String) = ActivityPresentation(
        visualState = state,
        statusLabel = label,
        connectionLabel = "测试",
        freshnessLabel = "测试",
        readError = null,
        activities = emptyList(),
        isConfirmedAvailable = false,
    )
}
