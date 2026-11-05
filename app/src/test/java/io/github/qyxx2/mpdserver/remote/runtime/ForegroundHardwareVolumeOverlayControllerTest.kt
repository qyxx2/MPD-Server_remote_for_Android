package io.github.qyxx2.mpdserver.remote.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundHardwareVolumeOverlayControllerTest {
    @Test
    fun keyEventsShowUpdateAndResetTheVisibilityWindow() = kotlinx.coroutines.runBlocking {
        var content = HardwareVolumeOverlayContent(valueText = "50")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ForegroundHardwareVolumeOverlayController(
            scope = scope,
            presentationProvider = { content },
            onVolumeKey = { direction ->
                val current = content.valueText.toIntOrNull() ?: 50
                content = content.copy(valueText = (current + direction).toString(), pending = true)
            },
            hideAfterMillis = 120,
        )
        try {
            controller.onVolumeKey(1)
            assertTrue(controller.state.value.visible)
            assertEquals("51", controller.state.value.content.valueText)
            assertTrue(controller.state.value.content.pending)

            delay(75)
            content = content.copy(valueText = "51", pending = false)
            controller.refreshPresentation()
            assertEquals("51", controller.state.value.content.valueText)
            controller.onVolumeKey(1)
            assertEquals("52", controller.state.value.content.valueText)
            delay(75)
            assertTrue("the repeat key event must restart the visibility deadline", controller.state.value.visible)

            controller.onVolumeKey(-1)
            assertEquals("51", controller.state.value.content.valueText)
            delay(75)
            assertTrue(controller.state.value.visible)
            delay(70)
            assertFalse("the overlay must hide after the last key event", controller.state.value.visible)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun bringingOverlayAboveANewPopupChangesOnlyItsLayerOrderAndNotItsHideDeadline() = kotlinx.coroutines.runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ForegroundHardwareVolumeOverlayController(
            scope = scope,
            presentationProvider = { HardwareVolumeOverlayContent(valueText = "73") },
            onVolumeKey = {},
            hideAfterMillis = 120,
        )
        try {
            controller.onVolumeKey(1)
            val initialOrder = controller.state.value.windowOrderSerial
            delay(50)
            controller.bringToFront()
            assertEquals(initialOrder + 1L, controller.state.value.windowOrderSerial)
            delay(50)
            assertTrue("bringing the Popup forward must not restart the hide timer", controller.state.value.visible)
            delay(35)
            assertFalse("the original key deadline must remain in force", controller.state.value.visible)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun delayedServerUpdatesCannotReopenAnOverlayAfterItsTimerExpires() = kotlinx.coroutines.runBlocking {
        var content = HardwareVolumeOverlayContent(valueText = "73", pending = true, statusText = "调整中")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ForegroundHardwareVolumeOverlayController(
            scope = scope,
            presentationProvider = { content },
            onVolumeKey = {},
            hideAfterMillis = 60,
        )
        try {
            controller.onVolumeKey(1)
            delay(90)
            assertFalse(controller.state.value.visible)

            content = HardwareVolumeOverlayContent(valueText = "74", pending = false)
            controller.refreshPresentation()
            assertFalse("readback may update retained content but must not re-show the overlay", controller.state.value.visible)
            assertEquals("74", controller.state.value.content.valueText)
        } finally {
            controller.close()
            scope.cancel()
        }
    }
    @Test
    fun defaultVisibilityWindowIsTwoSecondsAndServerReadbackDoesNotExtendIt() = kotlinx.coroutines.runBlocking {
        var content = HardwareVolumeOverlayContent(valueText = "73", pending = true, statusText = "调整中")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ForegroundHardwareVolumeOverlayController(
            scope = scope,
            presentationProvider = { content },
            onVolumeKey = {},
        )
        try {
            assertEquals(2_000L, ForegroundHardwareVolumeOverlayController.HIDE_AFTER_MILLIS)
            controller.onVolumeKey(1)
            delay(1_900)
            assertTrue(controller.state.value.visible)

            content = HardwareVolumeOverlayContent(valueText = "74", pending = false)
            controller.refreshPresentation()
            delay(50)
            assertTrue("the overlay must remain visible at 1,950ms", controller.state.value.visible)
            delay(100)
            assertFalse("the last key event must still hide the overlay at two seconds", controller.state.value.visible)
            assertEquals("74", controller.state.value.content.valueText)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

}
