package io.github.qyxx2.mpdserver.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVolumeOverlayPlacementTest {
    @Test
    fun floatingBarStartsEightDpBelowTheActualCutoutRingAndAvoidsSystemIcons() {
        val geometry = StatusRingWindowGeometry(
            cutoutBounds = listOf(StatusRingRect(left = 520, top = 0, right = 600, bottom = 60)),
            safeInsets = StatusRingInsets(left = 0, top = 90, right = 0, bottom = 72),
        )
        val placement = HardwareVolumeOverlayPlacementCalculator.calculate(
            windowWidthPx = 1_200,
            windowHeightPx = 2_800,
            geometry = geometry,
            density = 2f,
            preferences = StatusRingPlacementSettings(),
            barHeightPx = 56,
        )

        val ring = StatusRingPlacementCalculator.calculate(
            screenWidthPx = 1_200,
            screenHeightPx = 2_800,
            safeInsets = geometry.safeInsets,
            cutoutBounds = geometry.cutoutBounds,
            density = 2f,
        )
        val ringBottom = ring.centerYpx + ring.diameterPx / 2f
        assertEquals(600f, placement.centerXpx, 0.1f)
        assertTrue(placement.topPx >= ringBottom + 16f)
        assertTrue(placement.topPx >= geometry.safeInsets.top + 16f)
        assertTrue(placement.topPx + 56f <= 2_800f - geometry.safeInsets.bottom)
        assertEquals(StatusRingAnchorSource.DISPLAY_CUTOUT, placement.anchorSource)
    }

    @Test
    fun manualFallbackUsesTheSameCalibratedRingGeometryWhenCutoutIsUnavailable() {
        val geometry = StatusRingWindowGeometry(
            safeInsets = StatusRingInsets(left = 24, top = 80, right = 24, bottom = 90),
        )
        val settings = StatusRingPlacementSettings(
            sizeScale = 1.2f,
            horizontalOffsetDp = 12f,
            verticalOffsetDp = 6f,
        )
        val placement = HardwareVolumeOverlayPlacementCalculator.calculate(
            windowWidthPx = 1_080,
            windowHeightPx = 2_400,
            geometry = geometry,
            density = 3f,
            preferences = settings,
            barHeightPx = 48,
        )
        val ring = StatusRingPlacementCalculator.calculate(
            screenWidthPx = 1_080,
            screenHeightPx = 2_400,
            safeInsets = geometry.safeInsets,
            cutoutBounds = emptyList(),
            density = 3f,
            settings = settings,
        )

        assertEquals(StatusRingAnchorSource.MANUAL_FALLBACK, placement.anchorSource)
        assertEquals(540f, placement.centerXpx, 0.1f)
        assertTrue(placement.topPx >= ring.centerYpx + ring.diameterPx / 2f + 24f)
        assertTrue(placement.topPx >= 104f)
    }
}
