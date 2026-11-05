package io.github.qyxx2.mpdserver.remote.runtime

import android.view.KeyEvent

/** One Activity/window input boundary; it never calls Android's local media-volume APIs. */
class ForegroundHardwareVolumeKeyInputAdapter(
    private val onVolumeStep: (direction: Int) -> Unit,
) {
    @Volatile
    private var foreground = false

    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
    }

    /** Consumes both DOWN and UP while foreground, but emits exactly one step per DOWN/repeat. */
    fun dispatch(keyCode: Int, action: Int): Boolean {
        if (!foreground) return false
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                onVolumeStep(direction)
                true
            }
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }
}
