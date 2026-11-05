package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.github.qyxx2.mpdserver.remote.runtime.ForegroundHardwareVolumeKeyInputAdapter

/** The Activity is the sole owner; shared app-owned windows forward keys to that same adapter. */
val LocalHardwareVolumeKeyInputAdapter = compositionLocalOf<ForegroundHardwareVolumeKeyInputAdapter?> { null }

internal fun Modifier.forwardForegroundVolumeKeys(): Modifier = composed {
    val adapter = LocalHardwareVolumeKeyInputAdapter.current
    if (adapter == null) this else onPreviewKeyEvent { event ->
        val keyCode = when (event.key) {
            Key.VolumeUp -> android.view.KeyEvent.KEYCODE_VOLUME_UP
            Key.VolumeDown -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            else -> return@onPreviewKeyEvent false
        }
        val action = when (event.type) {
            KeyEventType.KeyDown -> android.view.KeyEvent.ACTION_DOWN
            KeyEventType.KeyUp -> android.view.KeyEvent.ACTION_UP
            else -> return@onPreviewKeyEvent false
        }
        adapter.dispatch(keyCode, action)
    }
}
