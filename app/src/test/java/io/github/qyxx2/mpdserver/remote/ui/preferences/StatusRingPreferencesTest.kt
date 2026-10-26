package io.github.qyxx2.mpdserver.remote.ui.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusRingPreferencesTest {
    @Test
    fun defaultValuesMatchTheFrozenVisualSpecification() {
        assertEquals(1.00f, StatusRingPreferences.DEFAULT.sizeScale, 0.0001f)
        assertEquals(3.0f, StatusRingPreferences.DEFAULT.lineWidthDp, 0.0001f)
        assertEquals(0.0f, StatusRingPreferences.DEFAULT.horizontalOffsetDp, 0.0001f)
        assertEquals(0.0f, StatusRingPreferences.DEFAULT.verticalOffsetDp, 0.0001f)
    }

    @Test
    fun valuesAreClampedAndSnappedToTheSpecifiedSteps() {
        val normalized = StatusRingPreferences(
            sizeScale = 1.13f,
            lineWidthDp = 4.26f,
            horizontalOffsetDp = 11f,
            verticalOffsetDp = -25f,
        ).normalized()

        assertEquals(1.15f, normalized.sizeScale, 0.0001f)
        assertEquals(4.5f, normalized.lineWidthDp, 0.0001f)
        assertEquals(12f, normalized.horizontalOffsetDp, 0.0001f)
        assertEquals(-24f, normalized.verticalOffsetDp, 0.0001f)
    }

    @Test
    fun everySettingClampsToItsFrozenMinimumAndMaximum() {
        val minimum = StatusRingPreferences(
            sizeScale = -10f,
            lineWidthDp = -10f,
            horizontalOffsetDp = -100f,
            verticalOffsetDp = -100f,
        ).normalized()
        val maximum = StatusRingPreferences(
            sizeScale = 100f,
            lineWidthDp = 100f,
            horizontalOffsetDp = 100f,
            verticalOffsetDp = 100f,
        ).normalized()

        assertEquals(StatusRingPreferences.SIZE_SCALE_MIN, minimum.sizeScale, 0.0001f)
        assertEquals(StatusRingPreferences.LINE_WIDTH_MIN_DP, minimum.lineWidthDp, 0.0001f)
        assertEquals(StatusRingPreferences.POSITION_OFFSET_MIN_DP, minimum.horizontalOffsetDp, 0.0001f)
        assertEquals(StatusRingPreferences.POSITION_OFFSET_MIN_DP, minimum.verticalOffsetDp, 0.0001f)
        assertEquals(StatusRingPreferences.SIZE_SCALE_MAX, maximum.sizeScale, 0.0001f)
        assertEquals(StatusRingPreferences.LINE_WIDTH_MAX_DP, maximum.lineWidthDp, 0.0001f)
        assertEquals(StatusRingPreferences.POSITION_OFFSET_MAX_DP, maximum.horizontalOffsetDp, 0.0001f)
        assertEquals(StatusRingPreferences.POSITION_OFFSET_MAX_DP, maximum.verticalOffsetDp, 0.0001f)
    }

    @Test
    fun nonFiniteValuesResolveToTheirDefaults() {
        val normalized = StatusRingPreferences(
            sizeScale = Float.NaN,
            lineWidthDp = Float.POSITIVE_INFINITY,
            horizontalOffsetDp = Float.NaN,
            verticalOffsetDp = Float.NEGATIVE_INFINITY,
        ).normalized()

        assertEquals(StatusRingPreferences.DEFAULT, normalized)
    }
}
