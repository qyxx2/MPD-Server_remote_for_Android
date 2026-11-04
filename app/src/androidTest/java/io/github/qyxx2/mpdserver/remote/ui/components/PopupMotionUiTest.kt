package io.github.qyxx2.mpdserver.remote.ui.components

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val MotionTestAlphaKey = SemanticsPropertyKey<Float>("MotionTestAlpha")
private val MotionTestScaleKey = SemanticsPropertyKey<Float>("MotionTestScale")

class PopupMotionUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openingAndReverseClosingAnimateAndKeepHostUntilFinished() {
        composeRule.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var dismissCount = 0
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.TRIGGER, Rect(10f, 20f, 50f, 60f))
            update(PopupMotionAnchorKind.OWNER, Rect(0f, 0f, 100f, 100f))
        }
        composeRule.setContent {
            PopupMotionHost(visible = visible, anchorState = anchor, onDismissComplete = { dismissCount++ }) { surfaceModifier, motion ->
                Box(
                    modifier = surfaceModifier.size(120.dp).testTag("motion-surface").semantics {
                        this[MotionTestAlphaKey] = motion.alpha
                        this[MotionTestScaleKey] = motion.scale
                    },
                )
            }
        }

        composeRule.runOnIdle { visible = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val openingStart = composeRule.onNodeWithTag("motion-surface", useUnmergedTree = true)
        assertEquals(0f, openingStart.fetchSemanticsNode().config[MotionTestAlphaKey], 0.0001f)
        assertEquals(0.82f, openingStart.fetchSemanticsNode().config[MotionTestScaleKey], 0.0001f)

        composeRule.mainClock.advanceTimeBy(90)
        val openingMid = composeRule.onNodeWithTag("motion-surface", useUnmergedTree = true)
        val openingAlpha = openingMid.fetchSemanticsNode().config[MotionTestAlphaKey]
        val openingScale = openingMid.fetchSemanticsNode().config[MotionTestScaleKey]
        assertTrue(openingAlpha in 0.05f..0.95f)
        assertTrue(openingScale in 0.82f..0.999f)

        composeRule.mainClock.advanceTimeBy(120)
        val opened = composeRule.onNodeWithTag("motion-surface", useUnmergedTree = true)
        assertEquals(1f, opened.fetchSemanticsNode().config[MotionTestAlphaKey], 0.0001f)
        assertEquals(1f, opened.fetchSemanticsNode().config[MotionTestScaleKey], 0.0001f)

        composeRule.runOnIdle { visible = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(90)
        val closingMid = composeRule.onNodeWithTag("motion-surface", useUnmergedTree = true)
        val closingAlpha = closingMid.fetchSemanticsNode().config[MotionTestAlphaKey]
        val closingScale = closingMid.fetchSemanticsNode().config[MotionTestScaleKey]
        assertTrue(closingAlpha in 0.05f..0.95f)
        assertTrue(closingScale in 0.82f..0.999f)

        composeRule.mainClock.advanceTimeBy(120)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("motion-surface", useUnmergedTree = true).assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
        composeRule.mainClock.advanceTimeBy(360)
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }
    @Test
    fun fullWindowDialogDismissesOnBackAndFinishesItsReverseTransition() {
        composeRule.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var dismissed = 0
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, Rect(20f, 30f, 180f, 110f))
            update(PopupMotionAnchorKind.TRIGGER, Rect(40f, 45f, 90f, 95f))
        }
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Column {
                    Button(onClick = { visible = true }, modifier = Modifier.testTag("dialog-back-trigger")) {
                        Text("Open")
                    }
                    MotionAlertDialog(
                        visible = visible,
                        onDismissRequest = { visible = false; dismissed++ },
                        anchorState = anchor,
                        dialogTitle = "Back dismissal test",
                        modifier = Modifier.testTag("dialog-back-surface"),
                        title = { Text("Back dismissal") },
                        text = { Text("Press Back to close") },
                        confirmButton = { TextButton(onClick = { visible = false }) { Text("Close") } },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("dialog-back-trigger").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(210)
        composeRule.onNodeWithTag("dialog-back-surface", useUnmergedTree = true).assertIsDisplayed()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.runOnIdle {
            assertFalse("Back must update the dialog owner state", visible)
            assertEquals("Back must deliver one dismissal callback", 1, dismissed)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(90)
        val closing = composeRule.onNodeWithTag("dialog-back-surface", useUnmergedTree = true)
        val closingAlpha = closing.fetchSemanticsNode().config[PopupMotionAlphaKey]
        val closingScale = closing.fetchSemanticsNode().config[PopupMotionScaleKey]
        assertTrue("Back close alpha=$closingAlpha", closingAlpha in 0.05f..0.95f)
        assertTrue("Back close scale=$closingScale", closingScale in 0.82f..0.999f)

        composeRule.mainClock.advanceTimeBy(120)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("dialog-back-surface", useUnmergedTree = true).assertCountEquals(0)
        composeRule.runOnIdle { assertEquals("Back dismissal callback must remain one-shot", 1, dismissed) }
    }

    @Test
    fun fullWindowDialogUsesTriggerOriginAndKeepsItsWindowDuringReverseClose() {
        composeRule.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var dismissed = 0
        val anchor = PopupMotionAnchorState()
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Column {
                    Button(
                        onClick = { visible = true },
                        modifier = Modifier
                            .popupMotionAnchor(anchor, PopupMotionAnchorKind.OWNER)
                            .popupMotionAnchor(anchor, PopupMotionAnchorKind.TRIGGER)
                            .testTag("dialog-motion-trigger"),
                    ) { Text("Open") }
                    MotionAlertDialog(
                        visible = visible,
                        onDismissRequest = { visible = false; dismissed++ },
                        anchorState = anchor,
                        dialogTitle = "Motion dialog test",
                        modifier = Modifier.testTag("motion-dialog-surface"),
                        title = { Text("Motion dialog") },
                        text = { Text("Dialog content") },
                        confirmButton = { TextButton(onClick = { visible = false }) { Text("Close") } },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("dialog-motion-trigger").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val openingStart = composeRule.onNodeWithTag("motion-dialog-surface", useUnmergedTree = true)
        assertEquals(0f, openingStart.fetchSemanticsNode().config[PopupMotionAlphaKey], 0.0001f)
        assertEquals(0.82f, openingStart.fetchSemanticsNode().config[PopupMotionScaleKey], 0.0001f)

        composeRule.mainClock.advanceTimeBy(210)
        assertEquals(1f, composeRule.onNodeWithTag("motion-dialog-surface", useUnmergedTree = true)
            .fetchSemanticsNode().config[PopupMotionAlphaKey], 0.0001f)
        composeRule.onNodeWithTag("app-dialog-scrim").performTouchInput {
            // Tap the exposed lower corner of the full-window scrim, not the center where
            // the dialog surface intercepts pointer input.
            click(Offset(24f, 24f))
        }
        composeRule.runOnIdle {
            assertFalse("scrim dismissal must change the owner visible state", visible)
            assertEquals("scrim dismissal callback must be delivered once", 1, dismissed)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(90)
        val closing = composeRule.onNodeWithTag("motion-dialog-surface", useUnmergedTree = true)
        val closingAlpha = closing.fetchSemanticsNode().config[PopupMotionAlphaKey]
        val closingScale = closing.fetchSemanticsNode().config[PopupMotionScaleKey]
        assertTrue("dialog closing alpha=$closingAlpha", closingAlpha in 0.05f..0.95f)
        assertTrue("dialog closing scale=$closingScale", closingScale in 0.82f..0.999f)
        composeRule.mainClock.advanceTimeBy(120)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("motion-dialog-surface", useUnmergedTree = true).assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

}
