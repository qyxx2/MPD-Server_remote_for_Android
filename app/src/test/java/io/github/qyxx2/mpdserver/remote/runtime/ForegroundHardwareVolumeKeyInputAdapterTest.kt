package io.github.qyxx2.mpdserver.remote.runtime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundHardwareVolumeKeyInputAdapterTest {
    @Test
    fun eachForegroundDownIncludingRepeatProducesOneStepAndUpProducesNone() {
        val steps = mutableListOf<Int>()
        val adapter = ForegroundHardwareVolumeKeyInputAdapter(onVolumeStep = { steps += it })
        adapter.setForeground(true)

        assertTrue(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertTrue(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        assertTrue(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))

        assertEquals(listOf(1, 1, -1), steps)
    }

    @Test
    fun volumeKeysFallThroughWhenActivityIsNotResumed() {
        val steps = mutableListOf<Int>()
        val adapter = ForegroundHardwareVolumeKeyInputAdapter(onVolumeStep = { steps += it })

        assertFalse(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        adapter.setForeground(true)
        assertTrue(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        adapter.setForeground(false)
        assertFalse(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))

        assertEquals(listOf(1), steps)
    }

    @Test
    fun unrelatedKeysAndUnsupportedActionsAreNotConsumed() {
        val adapter = ForegroundHardwareVolumeKeyInputAdapter(onVolumeStep = {})
        adapter.setForeground(true)

        assertFalse(adapter.dispatch(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN))
        assertFalse(adapter.dispatch(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_MULTIPLE))
    }

}
