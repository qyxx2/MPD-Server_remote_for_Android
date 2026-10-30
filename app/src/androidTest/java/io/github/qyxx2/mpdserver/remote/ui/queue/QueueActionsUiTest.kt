package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QueueActionsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun currentRowsKeepWebRemoveActionButNotNextOrAdd() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredQueueRowMenu(
                    item = item("current", 0),
                    title = "Current",
                    enabled = true,
                    onDismiss = {},
                    onMoveNext = {},
                    onMoveToEnd = {},
                    onRemove = {},
                )
            }
        }
        composeRule.onNodeWithTag("queue-row-menu-dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("queue-menu-next").assertCountEquals(0)
        composeRule.onAllNodesWithTag("queue-menu-add").assertCountEquals(0)
        composeRule.onNodeWithTag("queue-menu-remove").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-menu-cancel").assertIsDisplayed()
    }

    @Test
    fun rowMenuIsCompactAndOmitsDuplicateSongDetails() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredQueueRowMenu(
                    item = item("pending-compact", 2),
                    title = "Track title",
                    enabled = true,
                    onDismiss = {},
                    onMoveNext = {},
                    onMoveToEnd = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithTag("queue-row-menu-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-menu-title").assertIsDisplayed()
        composeRule.onAllNodesWithText("Artist metadata should be hidden").assertCountEquals(0)
        composeRule.onAllNodesWithText("Album metadata should be hidden").assertCountEquals(0)

        val panel = composeRule.onNodeWithTag("queue-row-menu-panel").fetchSemanticsNode().boundsInRoot
        val screenWidth = composeRule.activity.resources.displayMetrics.widthPixels.toFloat()
        val title = composeRule.onNodeWithTag("queue-menu-title").fetchSemanticsNode().boundsInRoot
        val optionTags = listOf("queue-menu-next-label", "queue-menu-add-label", "queue-menu-remove-label", "queue-menu-cancel-label")
        val optionCenters = optionTags.map { tag ->
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        }
        assertTrue("menu should be noticeably narrower than the available screen", panel.width < screenWidth * 0.80f)
        assertTrue("title should be centered in the menu", kotlin.math.abs(panel.center.x - title.center.x) < 2f)
        assertTrue("every option should be centered in the menu", optionCenters.all { kotlin.math.abs(panel.center.x - it) < 2f })
        val density = composeRule.density.density
        listOf("queue-menu-next", "queue-menu-add", "queue-menu-remove", "queue-menu-cancel").forEach { tag ->
            val heightDp = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / density
            assertTrue("menu options should keep a comfortable but compact 48dp target", heightDp >= 48f)
        }
        val labelHeightDp = composeRule.onNodeWithTag(
            "queue-menu-next-label",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("menu action text should fill more of the touch area", labelHeightDp >= 20f)
    }

    @Test
    fun pendingRowMenuEmitsExactlyTheChosenAction() {
        var chosen = ""
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredQueueRowMenu(
                    item = item("pending-duplicate-song", 2),
                    title = "Duplicate title",
                    enabled = true,
                    onDismiss = {},
                    onMoveNext = { chosen = "next" },
                    onMoveToEnd = { chosen = "end" },
                    onRemove = { chosen = "remove" },
                )
            }
        }

        composeRule.onNodeWithTag("queue-menu-next").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("next", chosen) }
        composeRule.onNodeWithTag("queue-menu-add").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-menu-remove").assertIsDisplayed()
    }

    @Test
    fun clearDialogCancelNeverCallsClearAndConfirmationDoes() {
        var clearCount = 0
        var dismissCount = 0
        val dialogState = mutableStateOf<QueueToolDialog?>(QueueToolDialog.CLEAR)
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueToolsDialog(
                    dialog = dialogState.value,
                    enabled = true,
                    onDismiss = { dismissCount++; dialogState.value = null },
                    onClear = { clearCount++ },
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithTag("queue-clear-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-clear-cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, clearCount)
            assertEquals(1, dismissCount)
            dialogState.value = QueueToolDialog.CLEAR
        }
        composeRule.onNodeWithTag("queue-clear-confirm").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, clearCount) }
    }

    @Test
    fun saveDialogRequiresNonBlankNameAndReturnsTheExactEnteredValue() {
        var saved: String? = null
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueToolsDialog(
                    dialog = QueueToolDialog.SAVE_PLAYLIST,
                    enabled = true,
                    onDismiss = {},
                    onClear = {},
                    onSave = { saved = it },
                )
            }
        }

        composeRule.onNodeWithTag("queue-save-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-save-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("queue-playlist-name").performTextInput("  Road Trip  ")
        composeRule.onNodeWithTag("queue-save-confirm").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("  Road Trip  ", saved) }
    }

    @Test
    fun readbackFailureOffersOnlyAReadRetryAndNeverAnotherSaveSubmission() {
        var saveCount = 0
        var readRetryCount = 0
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueToolsDialog(
                    dialog = QueueToolDialog.SAVE_PLAYLIST,
                    enabled = false,
                    onDismiss = {},
                    onClear = {},
                    onSave = { saveCount++ },
                    saveFeedback = "保存请求已确认，但读取歌单列表失败",
                    saveAlreadyConfirmed = true,
                    saveReadbackOnlyRetry = true,
                    onRetrySaveReadback = { readRetryCount++ },
                )
            }
        }
        composeRule.onNodeWithTag("queue-save-feedback").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-playlist-name").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-playlist-name").assertIsNotEnabled()
        composeRule.onNodeWithTag("queue-save-confirm").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(0, saveCount)
            assertEquals(1, readRetryCount)
        }
    }

    @Test
    fun originChangeAfterConfirmedSaveCannotOfferASecondWrite() {
        var saveCount = 0
        var readRetryCount = 0
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueToolsDialog(
                    dialog = QueueToolDialog.SAVE_PLAYLIST,
                    enabled = true,
                    onDismiss = {},
                    onClear = {},
                    onSave = { saveCount++ },
                    saveFeedback = "服务器地址已变化；原服务器的保存结果未能确认，请勿重复提交",
                    saveAlreadyConfirmed = true,
                    saveReadbackOnlyRetry = false,
                    onRetrySaveReadback = { readRetryCount++ },
                )
            }
        }
        composeRule.onNodeWithTag("queue-save-feedback").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-playlist-name").assertIsNotEnabled()
        composeRule.onNodeWithTag("queue-save-confirm").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(0, saveCount)
            assertEquals(0, readRetryCount)
        }
    }

    @Test
    fun ordinarySaveDialogRequiresNonBlankNameAndReturnsTheExactEnteredValue() {
        var saved: String? = null
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueToolsDialog(
                    dialog = QueueToolDialog.SAVE_PLAYLIST,
                    enabled = true,
                    onDismiss = {},
                    onClear = {},
                    onSave = { saved = it },
                )
            }
        }

        composeRule.onNodeWithTag("queue-save-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-save-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("queue-playlist-name").performTextInput("  Road Trip  ")
        composeRule.onNodeWithTag("queue-save-confirm").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("  Road Trip  ", saved) }
    }

    @Composable
    private fun AnchoredQueueRowMenu(
        item: QueueItemDto,
        title: String,
        enabled: Boolean,
        onDismiss: () -> Unit,
        onMoveNext: () -> Unit,
        onMoveToEnd: () -> Unit,
        onRemove: () -> Unit,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(48.dp)) {
                IconButton(onClick = {}) {}
                QueueRowMenu(
                    item = item,
                    title = title,
                    enabled = enabled,
                    onDismiss = onDismiss,
                    onMoveNext = onMoveNext,
                    onMoveToEnd = onMoveToEnd,
                    onRemove = onRemove,
                )
            }
        }
    }

    private fun item(id: String, position: Int) =
        QueueItemDto(id, "duplicate-song", position, QueueSourceDto.MANUAL, null)
}
