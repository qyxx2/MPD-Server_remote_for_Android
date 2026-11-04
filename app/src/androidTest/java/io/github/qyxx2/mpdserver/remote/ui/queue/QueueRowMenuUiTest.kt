package io.github.qyxx2.mpdserver.remote.ui.queue

import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class QueueRowMenuUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var expanded: MutableState<Boolean>
    private lateinit var title: MutableState<String>
    private var dismissCount = 0

    @Test
    fun queueRowMenuUsesLightCompactSurface() {
        showMenu()
        val menuNode = composeRule.onNodeWithTag("queue-row-menu-dialog").assertIsDisplayed().fetchSemanticsNode()
        val visualStyle = menuNode.config
        assertEquals("menu surface alpha remains 94%", 0.94f, visualStyle[QueueMenuSurfaceAlphaKey], 0.001f)
        assertEquals("menu corner radius remains 18dp", 18f, visualStyle[QueueMenuCornerRadiusKey], 0.001f)
        assertEquals("menu shadow elevation remains 7dp", 7f, visualStyle[QueueMenuShadowElevationKey], 0.001f)
        assertEquals("menu tonal elevation remains zero", 0f, visualStyle[QueueMenuTonalElevationKey], 0.001f)
        val pixels = composeRule.onNodeWithTag("queue-row-menu-dialog").captureToImage().toPixelMap()
        val density = composeRule.density.density
        val sampleX = (pixels.width - 4.dp.toPx(density)).toInt().coerceIn(0, pixels.width - 1)
        val sampleY = pixels.height / 2
        val surfacePixel = pixels[sampleX, sampleY]
        assertWhiteSurfaceOverKnownBackground(surfacePixel)

        // A point inside an 18dp top-left corner but outside the old 22dp corner
        // distinguishes the requested radius from the previous menu geometry.
        val radiusProbe = pixels[(3f * density).toInt(), (16f * density).toInt()]
        assertColorNear(
            "the 18dp corner should already be inside the menu surface",
            surfacePixel,
            radiusProbe,
        )

        val origin = menuNode.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val shadowX = (origin.x - 2.dp.toPx(density)).toInt().coerceAtLeast(0)
        val shadowY = (origin.y + menuNode.size.height / 2f).toInt().coerceIn(0, screen.height - 1)
        val shadowPixel = Color(screen.getPixel(shadowX, shadowY))
        val background = TEST_BACKGROUND
        assertTrue(
            "the menu surface must cast a visible shadow outside its bounds; background=$background shadow=$shadowPixel",
            colorDistance(background, shadowPixel) > 0.006f,
        )
        screen.recycle()
    }

    @Test
    fun queueRowMenuActionsUseOneIconColumnAndLeftTextEdge() {
        showMenu()
        val actionIds = listOf("next", "add", "remove", "cancel")
        val iconBounds = actionIds.map { id ->
            composeRule.onNodeWithTag("queue-menu-icon-column-$id", useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }
        val labelBounds = actionIds.map { id ->
            composeRule.onNodeWithTag("queue-menu-$id-label", useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }
        val density = composeRule.density.density
        iconBounds.forEach { bounds ->
            assertEquals("every action must reserve a 22dp icon column", 22f, bounds.width / density, 0.75f)
        }
        assertTrue(
            "all action labels must share the same left edge",
            labelBounds.maxOf { it.left } - labelBounds.minOf { it.left } < 1f,
        )
        iconBounds.zip(labelBounds).forEach { (icon, label) ->
            assertEquals("the label must follow the icon column by a consistent 8dp gap", 8f, (label.left - icon.right) / density, 0.75f)
        }
    }

    @Test
    fun queueRowMenuWidthComesFromLongestActionNotSongTitle() {
        showMenu(titleText = "短标题")
        val menu = composeRule.onNodeWithTag("queue-row-menu-dialog").fetchSemanticsNode().boundsInRoot
        val labelWidths = listOf("next", "add", "remove", "cancel").map { id ->
            composeRule.onNodeWithTag("queue-menu-$id-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        }
        val density = composeRule.density.density
        val longestLabel = labelWidths.maxOrNull()!!
        val expectedWidthDp = longestLabel / density + 22f + 8f + 32f
        assertEquals(
            "menu width must be the longest action label plus the fixed icon/gap, 6dp row insets and 10dp panel insets",
            expectedWidthDp,
            menu.width / density,
            2f,
        )

        val shortWidth = menu.width
        title.value = "An exceptionally long song title which must be ellipsized and must never widen the queue menu"
        composeRule.waitForIdle()
        val longMenu = composeRule.onNodeWithTag("queue-row-menu-dialog").fetchSemanticsNode().boundsInRoot
        val titleBounds = composeRule.onNodeWithTag("queue-menu-title", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val actionColumn = composeRule.onNodeWithTag("queue-menu-action-column").fetchSemanticsNode().boundsInRoot
        assertEquals("a long song title must not change the action-defined menu width", shortWidth, longMenu.width, 1f)
        assertTrue("the title must be constrained to the measured action column", titleBounds.width <= actionColumn.width + 1f)
    }

    @Test
    fun queueRowMenuDismissesOnBackAndOutsideTap() {
        showMenu()
        composeRule.onNodeWithTag("queue-row-menu-dialog").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("queue-row-menu-dialog").assertCountEquals(0)
        assertEquals("Back must invoke the existing dismissal callback once", 1, dismissCount)

        expanded.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("queue-row-menu-dialog").assertIsDisplayed()
        val decor = composeRule.activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val x = location[0] + decor.width * 0.10f
        val y = location[1] + decor.height * 0.86f
        val downTime = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
            instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("queue-row-menu-dialog").assertCountEquals(0)
        assertEquals("outside tap must invoke the existing dismissal callback once more", 2, dismissCount)
    }

    private fun showMenu(titleText: String = "Queue track") {
        expanded = mutableStateOf(true)
        title = mutableStateOf(titleText)
        dismissCount = 0
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(Modifier.fillMaxSize().background(TEST_BACKGROUND)) {
                    Box(
                        modifier = Modifier.align(Alignment.Center).size(48.dp).testTag("queue-menu-anchor"),
                    ) {
                        QueueRowMenu(
                            item = QueueItemDto("queue-occurrence", "song-id", 2, QueueSourceDto.MANUAL, null),
                            title = title.value,
                            enabled = true,
                            onDismiss = { dismissCount++; expanded.value = false },
                            onMoveNext = {},
                            onMoveToEnd = {},
                            onRemove = {},
                            expanded = expanded.value,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("queue-row-menu-dialog").assertIsDisplayed()
    }

    private fun assertWhiteSurfaceOverKnownBackground(actual: Color) {
        val rawWhite = Color.White.copy(alpha = 0.94f)
        val compositedWhite = rawWhite.compositeOver(TEST_BACKGROUND)
        assertTrue(
            "queue menu must render a 94%-opaque white surface; actual=$actual raw=$rawWhite composited=$compositedWhite",
            colorDistance(actual, rawWhite) < 0.035f || colorDistance(actual, compositedWhite) < 0.035f,
        )
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color) {
        assertTrue("$message; expected=$expected actual=$actual", colorDistance(expected, actual) < 0.035f)
    }

    private fun colorDistance(first: Color, second: Color): Float =
        abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)


    private fun androidx.compose.ui.unit.Dp.toPx(density: Float): Float = value * density




    private companion object {
        val TEST_BACKGROUND = Color(0xFF17324D)
    }
}
