package io.github.qyxx2.mpdserver.remote.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackServiceAvailabilityTest {
    @Test
    fun blockedBackgroundServiceStartIsExplainedWithoutChangingNotificationPermissionFacts() {
        val state = MediaControlAvailability.fromPlatform(
            apiLevel = 36,
            hasPostNotificationsPermission = true,
            appNotificationsEnabled = true,
            channelImportance = 2,
            serviceStartFailure = "app is in background",
        )

        assertTrue(state.ordinaryNotificationsAllowed)
        assertTrue(state.statusMessage.contains("后台"))
        assertTrue(state.statusMessage.contains("app is in background"))
    }

    @Test
    fun serviceStartFailureAndNotificationDenialRemainIndependentDiagnostics() {
        val state = MediaControlAvailability.fromPlatform(
            apiLevel = 36,
            hasPostNotificationsPermission = false,
            appNotificationsEnabled = true,
            channelImportance = 2,
            serviceStartFailure = "app is in background",
        )

        assertFalse(state.ordinaryNotificationsAllowed)
        assertTrue(state.statusMessage.contains("后台"))
        assertTrue(state.statusMessage.contains("通知权限未授予"))
    }
}
