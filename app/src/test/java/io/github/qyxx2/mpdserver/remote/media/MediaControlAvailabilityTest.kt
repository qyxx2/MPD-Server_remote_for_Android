package io.github.qyxx2.mpdserver.remote.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlAvailabilityTest {
    @Test
    fun api32DoesNotRequireRuntimeNotificationPermission() {
        val state = MediaControlAvailability.fromPlatform(
            apiLevel = 32,
            hasPostNotificationsPermission = false,
            appNotificationsEnabled = true,
            channelImportance = 2,
        )

        assertEquals(NotificationPermissionStatus.NOT_REQUIRED, state.permission)
        assertTrue(state.channelEnabled)
        assertTrue(state.ordinaryNotificationsAllowed)
    }

    @Test
    fun api33DeniedPermissionIsReportedSeparatelyFromMediaSessionCapability() {
        val state = MediaControlAvailability.fromPlatform(
            apiLevel = 33,
            hasPostNotificationsPermission = false,
            appNotificationsEnabled = false,
            channelImportance = 2,
        )

        assertEquals(NotificationPermissionStatus.DENIED, state.permission)
        assertTrue(state.channelEnabled)
        assertFalse(state.ordinaryNotificationsAllowed)
        assertTrue(state.statusMessage.contains("MediaSession"))
        assertTrue(state.statusMessage.contains("通知权限"))
    }

    @Test
    fun channelDisabledIsReportedWithoutSuggestingAReplacementChannel() {
        val state = MediaControlAvailability.fromPlatform(
            apiLevel = 36,
            hasPostNotificationsPermission = true,
            appNotificationsEnabled = true,
            channelImportance = 0,
        )

        assertEquals(NotificationPermissionStatus.GRANTED, state.permission)
        assertFalse(state.channelEnabled)
        assertFalse(state.ordinaryNotificationsAllowed)
        assertTrue(state.statusMessage.contains("渠道"))
        assertTrue(state.openSettingsLabel.isNotBlank())
    }

    @Test
    fun appWideNotificationSwitchIsReportedWhenPermissionWasGranted() {
        val state = MediaControlAvailability.fromPlatform(
            apiLevel = 36,
            hasPostNotificationsPermission = true,
            appNotificationsEnabled = false,
            channelImportance = 2,
        )

        assertFalse(state.ordinaryNotificationsAllowed)
        assertTrue(state.statusMessage.contains("系统设置"))
    }
}
