package io.github.qyxx2.mpdserver.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StatusRingPlacementTest {
    @Test
    fun reliableCenteredCutoutIsTheActualAnchorAndRingEnclosesItsBounds() {
        val cutout = StatusRingRect(left = 510, top = 0, right = 570, bottom = 60)

        val placement = StatusRingPlacementCalculator.calculate(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            safeInsets = StatusRingInsets(left = 0, top = 144, right = 0, bottom = 90),
            cutoutBounds = listOf(cutout),
            density = 3f,
        )

        assertEquals(StatusRingAnchorSource.DISPLAY_CUTOUT, placement.anchorSource)
        assertEquals(540f, placement.centerXpx, 0.01f)
        assertEquals(30f, placement.centerYpx, 0.01f)
        assertTrue(
            "The outer diameter must enclose the complete cutout bounding rectangle with a gap",
            placement.diameterPx > hypot(cutout.width.toFloat(), cutout.height.toFloat()),
        )
    }

    @Test
    fun missingEmptyOffCenterAndOutOfWindowCutoutsUseExplicitManualFallback() {
        val safeInsets = StatusRingInsets(left = 0, top = 144, right = 0, bottom = 90)
        val unusableGeometry = listOf(
            emptyList(),
            listOf(StatusRingRect(40, 0, 100, 60)),
            listOf(StatusRingRect(-20, 0, 40, 60)),
            listOf(StatusRingRect(510, 400, 570, 460)),
            listOf(StatusRingRect(540, 0, 540, 60)),
        )

        unusableGeometry.forEach { cutouts ->
            val placement = StatusRingPlacementCalculator.calculate(
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                safeInsets = safeInsets,
                cutoutBounds = cutouts,
                density = 3f,
            )

            assertEquals(StatusRingAnchorSource.MANUAL_FALLBACK, placement.anchorSource)
            assertEquals(540f, placement.centerXpx, 0.01f)
            assertEquals(72f, placement.centerYpx, 0.01f)
        }
    }

    @Test
    fun offsetsAndScaleAreBoundedBySettingsAndSafeWindowEdges() {
        val placement = StatusRingPlacementCalculator.calculate(
            screenWidthPx = 120,
            screenHeightPx = 500,
            safeInsets = StatusRingInsets(left = 20, top = 60, right = 20, bottom = 40),
            cutoutBounds = listOf(StatusRingRect(55, 5, 65, 25)),
            density = 1f,
            settings = StatusRingPlacementSettings(
                sizeScale = 20f,
                horizontalOffsetDp = 200f,
                verticalOffsetDp = 200f,
            ),
        )

        assertEquals(StatusRingAnchorSource.DISPLAY_CUTOUT, placement.anchorSource)
        assertTrue("Ring must fit inside the available horizontal safe bounds", placement.diameterPx <= 80f)
        assertTrue(placement.centerXpx - placement.diameterPx / 2f >= 20f - 0.01f)
        assertTrue(placement.centerXpx + placement.diameterPx / 2f <= 100f + 0.01f)
        assertTrue("Offset must be clamped into the top safe region", placement.centerYpx <= 60f)
        assertTrue("Offset must not move the anchor above the window", placement.centerYpx >= 0f)
    }

    @Test
    fun aLargeCutoutAndScaleCannotExpandTheRingBeyondAvailableWidth() {
        val placement = StatusRingPlacementCalculator.calculate(
            screenWidthPx = 120,
            screenHeightPx = 500,
            safeInsets = StatusRingInsets(left = 20, top = 100, right = 20, bottom = 40),
            cutoutBounds = listOf(StatusRingRect(20, 0, 100, 80)),
            density = 1f,
            settings = StatusRingPlacementSettings(sizeScale = 1.4f),
        )

        assertEquals(StatusRingAnchorSource.DISPLAY_CUTOUT, placement.anchorSource)
        assertTrue(placement.diameterPx <= 80f)
        assertTrue(placement.centerXpx - placement.diameterPx / 2f >= 20f - 0.01f)
        assertTrue(placement.centerXpx + placement.diameterPx / 2f <= 100f + 0.01f)
    }
}
