package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeOverlayContent
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeOverlayState
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareVolumePopupZOrderUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun visibleVolumePopupCanBeRaisedAboveAnAppPopupOpenedLaterUsingRealDisplayCutoutInsets() {
        val screen = captureRealScreenGeometry()
        val preferences = StatusRingPreferences.DEFAULT
        var overlayState by mutableStateOf(
            HardwareVolumeOverlayState(
                visible = true,
                content = HardwareVolumeOverlayContent(
                    valueText = "74",
                    pending = true,
                    statusText = "调整中",
                ),
            ),
        )
        var showLaterPopup by mutableStateOf(false)

        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                if (showLaterPopup) {
                    Popup(
                        popupPositionProvider = topLeft(),
                        onDismissRequest = {},
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Box(
                            Modifier.width(320.dp)
                                .height(320.dp)
                                .background(Color.Red)
                        )
                    }
                }
                HardwareVolumeOverlayPopup(
                    state = overlayState,
                    geometry = screen.geometry,
                    preferences = preferences,
                    darkTheme = false,
                )
            }
        }
        composeRule.waitForIdle()

        val placement = calculatePlacement(screen, preferences)
        screen.geometry.cutoutBounds.maxOfOrNull { it.bottom }?.let { cutoutBottom ->
            assertTrue(
                "The strip must retain 8dp clearance below the real camera cutout",
                placement.topPx >= cutoutBottom + 8f * screen.density,
            )
        }

        composeRule.runOnIdle { showLaterPopup = true }
        composeRule.waitForIdle()
        assertSampleColor(
            screen = screen,
            x = placement.centerXpx,
            y = placement.topPx + 2f * screen.density,
            predicate = { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 80 },
            message = "The later focusable app Popup should initially be above the already visible volume Popup",
        )

        // MainActivity invokes bringToFront when the focusable app Popup takes window focus.
        composeRule.runOnIdle {
            overlayState = overlayState.copy(windowOrderSerial = overlayState.windowOrderSerial + 1L)
        }
        composeRule.waitForIdle()
        assertSampleColor(
            screen = screen,
            x = placement.centerXpx,
            y = placement.topPx + 2f * screen.density,
            predicate = { android.graphics.Color.green(it) > 150 },
            message = "The re-created non-focusable volume Popup must render above the later focusable app Popup",
        )
    }

    @Test
    fun motionAlertDialogHostsTheSameVolumePopupAboveItsScrimAndSurface() {
        val screen = captureRealScreenGeometry()
        val preferences = StatusRingPreferences.DEFAULT
        var dialogVisible by mutableStateOf(false)
        var overlayState by mutableStateOf(
            HardwareVolumeOverlayState(
                visible = true,
                content = HardwareVolumeOverlayContent(valueText = "74", statusText = "调整中"),
            ),
        )

        composeRule.setContent {
            val anchor = rememberPopupMotionAnchorState()
            val overlayEnvironment = HardwareVolumeOverlayEnvironment(
                state = overlayState,
                geometry = screen.geometry,
                preferences = preferences,
                darkTheme = false,
            )
            CompositionLocalProvider(
                LocalHardwareVolumeOverlayEnvironment provides overlayEnvironment,
            ) {
                Box(Modifier.fillMaxSize()) {
                    MotionAlertDialog(
                        visible = dialogVisible,
                        onDismissRequest = {},
                        anchorState = anchor,
                        dialogTitle = "Volume overlay z-order",
                        title = {},
                        text = {},
                        confirmButton = {},
                    )
                    HardwareVolumeOverlayPopup(
                        state = overlayState,
                        geometry = screen.geometry,
                        preferences = preferences,
                        darkTheme = false,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val placement = calculatePlacement(screen, preferences)
        composeRule.runOnIdle { dialogVisible = true }
        composeRule.waitForIdle()
        android.os.SystemClock.sleep(100L)

        assertSampleColor(
            screen = screen,
            x = placement.centerXpx,
            y = placement.topPx + 2f * screen.density,
            predicate = { android.graphics.Color.red(it) > 220 && android.graphics.Color.green(it) > 220 },
            message = "The Dialog-local volume Popup must render above the MotionAlertDialog scrim and surface",
        )
    }

    private fun captureRealScreenGeometry(): RealScreenGeometry {
        val activity = composeRule.activity
        var result: RealScreenGeometry? = null
        composeRule.runOnIdle {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val decor = activity.window.decorView
            ViewCompat.requestApplyInsets(decor)
            val rootInsets = ViewCompat.getRootWindowInsets(decor)
            val safe = rootInsets?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val cutouts = rootInsets?.displayCutout?.boundingRects.orEmpty()
                .map { StatusRingRect(it.left, it.top, it.right, it.bottom) }
            result = RealScreenGeometry(
                geometry = StatusRingWindowGeometry(
                    cutoutBounds = cutouts,
                    safeInsets = StatusRingInsets(
                        left = safe?.left ?: 0,
                        top = safe?.top ?: 0,
                        right = safe?.right ?: 0,
                        bottom = safe?.bottom ?: 0,
                    ),
                ),
                widthPx = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels,
                heightPx = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels,
                density = activity.resources.displayMetrics.density,
            )
        }
        composeRule.waitForIdle()
        return requireNotNull(result)
    }

    private fun calculatePlacement(
        screen: RealScreenGeometry,
        preferences: StatusRingPreferences,
    ): HardwareVolumeOverlayPlacement =
        HardwareVolumeOverlayPlacementCalculator.calculate(
            windowWidthPx = screen.widthPx,
            windowHeightPx = screen.heightPx,
            geometry = screen.geometry,
            density = screen.density,
            preferences = StatusRingPlacementSettings(
                sizeScale = preferences.sizeScale,
                horizontalOffsetDp = preferences.horizontalOffsetDp,
                verticalOffsetDp = preferences.verticalOffsetDp,
            ),
            barHeightPx = (48f * screen.density).toInt(),
        )

    private fun assertSampleColor(
        screen: RealScreenGeometry,
        x: Float,
        y: Float,
        predicate: (Int) -> Boolean,
        message: String,
    ) {
        val screenshot = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
        )
        val pixel = screenshot.getPixel(
            x.toInt().coerceIn(0, minOf(screen.widthPx, screenshot.width) - 1),
            y.toInt().coerceIn(0, minOf(screen.heightPx, screenshot.height) - 1),
        )
        assertTrue("$message; pixel=$pixel", predicate(pixel))
        screenshot.recycle()
    }

    private data class RealScreenGeometry(
        val geometry: StatusRingWindowGeometry,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
    )

    private fun topLeft() = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset = IntOffset.Zero
    }
}
