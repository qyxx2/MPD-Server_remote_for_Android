package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.runtime.compositionLocalOf
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeOverlayState
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences

/** Shared Activity-owned overlay projection exposed to app-owned Dialog windows. */
internal data class HardwareVolumeOverlayEnvironment(
    val state: HardwareVolumeOverlayState,
    val geometry: StatusRingWindowGeometry,
    val preferences: StatusRingPreferences,
    val darkTheme: Boolean,
)

internal val LocalHardwareVolumeOverlayEnvironment =
    compositionLocalOf<HardwareVolumeOverlayEnvironment?> { null }
