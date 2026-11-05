package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import io.github.qyxx2.mpdserver.remote.runtime.ForegroundHardwareVolumeKeyInputAdapter
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HardwareVolumeKeyDispatchUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun focusedTextInputInsideAppOwnedDialogForwardsVolumeKeysToTheSharedAdapter() {
        val steps = mutableListOf<Int>()
        val adapter = ForegroundHardwareVolumeKeyInputAdapter(onVolumeStep = { steps += it }).apply { setForeground(true) }
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 100f))
        }
        var text by mutableStateOf("")
        composeRule.setContent {
            CompositionLocalProvider(LocalHardwareVolumeKeyInputAdapter provides adapter) {
                MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                    MotionAlertDialog(
                        visible = true,
                        onDismissRequest = {},
                        anchorState = anchor,
                        dialogTitle = "Volume key dispatch test",
                        title = {},
                        text = {
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.testTag("dialog-volume-key-target"),
                            )
                        },
                        confirmButton = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog-volume-key-target").performClick()
        composeRule.onNodeWithTag("dialog-volume-key-target").performKeyInput { pressKey(Key.VolumeUp) }
        composeRule.runOnIdle { assertEquals(listOf(1), steps) }
    }

    @Test
    fun focusedTextInputInsideFocusablePopupForwardsVolumeKeysToTheSharedAdapter() {
        val steps = mutableListOf<Int>()
        val adapter = ForegroundHardwareVolumeKeyInputAdapter(onVolumeStep = { steps += it }).apply { setForeground(true) }
        var text by mutableStateOf("")
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, androidx.compose.ui.geometry.Rect(0f, 0f, 400f, 800f))
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalHardwareVolumeKeyInputAdapter provides adapter) {
                Box(Modifier.fillMaxSize().testTag("popup-volume-root")) {
                    PopupMotionHost(visible = true, anchorState = anchor) { surfaceModifier, _ ->
                        Popup(
                            onDismissRequest = {},
                            properties = PopupProperties(focusable = true, dismissOnBackPress = false),
                        ) {
                            Column(modifier = surfaceModifier.size(240.dp).focusable().testTag("popup-volume-surface")) {
                                BasicTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier = Modifier.testTag("popup-volume-key-target"),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("popup-volume-key-target").performClick()
        composeRule.onNodeWithTag("popup-volume-key-target").performKeyInput { pressKey(Key.VolumeDown) }
        composeRule.runOnIdle { assertEquals(listOf(-1), steps) }
    }
}
