package io.github.qyxx2.mpdserver.remote.media

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.settings.StatusSettingsScreen
import org.junit.Rule
import org.junit.Test

class StatusSettingsMediaAvailabilityUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deniedNotificationPermissionHasPreciseFallbackExplanationAndSettingsEntry() {
        val availability = MediaControlAvailability.fromPlatform(
            apiLevel = 33,
            hasPostNotificationsPermission = false,
            appNotificationsEnabled = false,
            channelImportance = 2,
        )
        composeRule.setContent {
            StatusSettingsScreen(
                presentation = ActivityPresentation.from(SnapshotStoreState(), emptyList()),
                mediaControlAvailability = availability,
                onRetryUnknown = {},
            )
        }

        composeRule.onNodeWithTag("media-control-availability").assertIsDisplayed()
        composeRule.onNodeWithTag("media-control-availability-message").assertIsDisplayed()
        composeRule.onNodeWithText("通知权限未授予。Android 可能仍会独立显示 MediaSession 媒体卡片，普通通知会受到限制。")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("media-control-open-system-settings").assertIsDisplayed()
    }
}
