package io.github.qyxx2.mpdserver.remote

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.runtime.ForegroundHardwareVolumeKeyInputAdapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityHardwareVolumeKeyInputUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityReceivesRealVolumeKeyEventAndRoutesItToTheSharedForegroundAdapter() {
        val steps = mutableListOf<Int>()
        val adapter = ForegroundHardwareVolumeKeyInputAdapter(onVolumeStep = { steps += it }).apply { setForeground(true) }
        val activity = composeRule.activity
        val original = activity.volumeKeyInputAdapter
        activity.volumeKeyInputAdapter = adapter
        try {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            composeRule.runOnIdle { assertEquals(listOf(1), steps) }
        } finally {
            activity.volumeKeyInputAdapter = original
            adapter.setForeground(false)
        }
    }
}
