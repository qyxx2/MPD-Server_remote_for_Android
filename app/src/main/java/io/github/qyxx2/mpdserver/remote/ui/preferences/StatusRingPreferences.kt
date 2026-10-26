package io.github.qyxx2.mpdserver.remote.ui.preferences

import kotlin.math.roundToInt

/** Local-only visual settings. Values are normalized to the single frozen specification here. */
data class StatusRingPreferences(
    val sizeScale: Float = SIZE_SCALE_DEFAULT,
    val lineWidthDp: Float = LINE_WIDTH_DEFAULT_DP,
    val horizontalOffsetDp: Float = POSITION_OFFSET_DEFAULT_DP,
    val verticalOffsetDp: Float = POSITION_OFFSET_DEFAULT_DP,
) {
    fun normalized(): StatusRingPreferences = StatusRingPreferences(
        sizeScale = snap(sizeScale, SIZE_SCALE_MIN, SIZE_SCALE_MAX, SIZE_SCALE_STEP, SIZE_SCALE_DEFAULT),
        lineWidthDp = snap(lineWidthDp, LINE_WIDTH_MIN_DP, LINE_WIDTH_MAX_DP, LINE_WIDTH_STEP_DP, LINE_WIDTH_DEFAULT_DP),
        horizontalOffsetDp = snap(
            horizontalOffsetDp,
            POSITION_OFFSET_MIN_DP,
            POSITION_OFFSET_MAX_DP,
            POSITION_OFFSET_STEP_DP,
            POSITION_OFFSET_DEFAULT_DP,
        ),
        verticalOffsetDp = snap(
            verticalOffsetDp,
            POSITION_OFFSET_MIN_DP,
            POSITION_OFFSET_MAX_DP,
            POSITION_OFFSET_STEP_DP,
            POSITION_OFFSET_DEFAULT_DP,
        ),
    )

    companion object {
        const val SIZE_SCALE_MIN = 0.70f
        const val SIZE_SCALE_MAX = 1.40f
        const val SIZE_SCALE_STEP = 0.05f
        const val SIZE_SCALE_DEFAULT = 1.00f
        const val SIZE_SCALE_SLIDER_STEPS = 13

        const val LINE_WIDTH_MIN_DP = 2.0f
        const val LINE_WIDTH_MAX_DP = 5.0f
        const val LINE_WIDTH_STEP_DP = 0.5f
        const val LINE_WIDTH_DEFAULT_DP = 3.0f
        const val LINE_WIDTH_SLIDER_STEPS = 5

        const val POSITION_OFFSET_MIN_DP = -24.0f
        const val POSITION_OFFSET_MAX_DP = 24.0f
        const val POSITION_OFFSET_STEP_DP = 2.0f
        const val POSITION_OFFSET_DEFAULT_DP = 0.0f
        const val POSITION_OFFSET_SLIDER_STEPS = 23

        val DEFAULT = StatusRingPreferences()

        private fun snap(value: Float, min: Float, max: Float, step: Float, default: Float): Float {
            if (!value.isFinite()) return default
            val maxIndex = ((max - min) / step).roundToInt()
            val index = ((value.coerceIn(min, max) - min) / step).roundToInt().coerceIn(0, maxIndex)
            return (min + index * step).coerceIn(min, max)
        }
    }
}
