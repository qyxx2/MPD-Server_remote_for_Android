package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCanonicalTuple
import kotlin.math.roundToInt

/** Gesture-local slider preview. It is deliberately not stored in AppRuntime or SnapshotStore. */
internal data class HardwareVolumeSliderDraft(
    val baseline: HardwareVolumeCanonicalTuple? = null,
    val previewPercent: Float? = null,
    val invalidated: Boolean = false,
) {
    fun preview(value: Float, current: HardwareVolumeCanonicalTuple?): HardwareVolumeSliderDraft {
        if (invalidated) return this
        val original = baseline ?: current ?: return copy(invalidated = true)
        if (original != current) return copy(previewPercent = null, invalidated = true)
        return copy(baseline = original, previewPercent = value.coerceIn(0f, 100f))
    }

    fun invalidateIfCanonicalChanged(current: HardwareVolumeCanonicalTuple?): HardwareVolumeSliderDraft =
        if (baseline != null && baseline != current) copy(previewPercent = null, invalidated = true) else this

    fun targetForRelease(
        current: HardwareVolumeCanonicalTuple?,
        canWrite: Boolean,
    ): Int? = previewPercent
        ?.roundToInt()
        ?.takeIf { !invalidated && baseline != null && baseline == current && canWrite && it in 0..100 }
}
