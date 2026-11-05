package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposePopupOrderingUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun laterNonFocusablePopupRendersAboveExistingFocusablePopup() {
        var showOverlay by mutableStateOf(false)
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
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
                if (showOverlay) {
                    Popup(
                        popupPositionProvider = topLeft(),
                        onDismissRequest = {},
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Box(
                            Modifier.width(300.dp)
                                .height(56.dp)
                                .background(Color.Green)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showOverlay = true }
        composeRule.waitForIdle()
        android.os.SystemClock.sleep(100L)

        val screenshot = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
        )
        val x = (composeRule.activity.resources.displayMetrics.density * 150f).toInt()
        val y = (composeRule.activity.resources.displayMetrics.density * 28f).toInt()
        val pixel = screenshot.getPixel(
            x.coerceIn(0, screenshot.width - 1),
            y.coerceIn(0, screenshot.height - 1),
        )
        assertTrue("A later non-focusable Compose Popup must be above the focusable one; pixel=$pixel", android.graphics.Color.green(pixel) > 200)
        screenshot.recycle()
    }

    private fun topLeft() = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset = IntOffset.Zero
    }
}
