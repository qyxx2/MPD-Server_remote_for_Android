package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QueueActionsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun currentRowsKeepWebRemoveActionButNotNextOrAdd() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueRowMenu(
                    item = item("current", 0),
                    title = "Current",
                    artist = "Artist",
                    album = "Album",
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
    fun pendingRowMenuEmitsExactlyTheChosenAction() {
        var chosen = ""
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueRowMenu(
                    item = item("pending-duplicate-song", 2),
                    title = "Duplicate title",
                    artist = "Artist",
                    album = "Album",
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

    private fun item(id: String, position: Int) =
        QueueItemDto(id, "duplicate-song", position, QueueSourceDto.MANUAL, null)
}
