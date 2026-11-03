package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTransitionGeometryTest {
    @Test
    fun progressZeroReturnsSourceBounds() {
        val source = Rect(10f, 20f, 110f, 220f)
        val target = Rect(210f, 120f, 410f, 520f)

        assertRectEquals(source, ArtworkTransitionGeometry.interpolate(source, target, 0f))
    }

    @Test
    fun progressOneReturnsTargetBounds() {
        val source = Rect(10f, 20f, 110f, 220f)
        val target = Rect(210f, 120f, 410f, 520f)

        assertRectEquals(target, ArtworkTransitionGeometry.interpolate(source, target, 1f))
    }

    @Test
    fun midpointInterpolatesAllFourEdges() {
        val source = Rect(10f, 20f, 110f, 220f)
        val target = Rect(210f, 120f, 410f, 520f)

        assertRectEquals(
            Rect(110f, 70f, 260f, 370f),
            ArtworkTransitionGeometry.interpolate(source, target, 0.5f),
        )
    }

    @Test
    fun dismissalUsesIndependentCoverShrinkAndTravelTrajectory() {
        val source = Rect(87f, 230f, 603f, 746f)
        val target = Rect(113f, 1088f, 285f, 1260f)

        // At 23% sheet drag, the reference frame shows the cover already shrunk to ~391px,
        // moved down to ~573px, while remaining near the horizontal center of the display.
        assertRectEquals(
            Rect(124.628f, 570.153f, 518.862f, 964.388f),
            ArtworkTransitionGeometry.interpolateDismissal(source, target, 0.23f),
            tolerance = 0.01f,
        )
    }

    @Test
    fun dismissalStartsAtFullscreenArtworkAndEndsAtMiniPlayerArtwork() {
        val source = Rect(10f, 20f, 110f, 120f)
        val target = Rect(210f, 320f, 260f, 370f)

        assertRectEquals(source, ArtworkTransitionGeometry.interpolateDismissal(source, target, 0f))
        assertRectEquals(target, ArtworkTransitionGeometry.interpolateDismissal(source, target, 1f))
        assertRectEquals(source, ArtworkTransitionGeometry.interpolateDismissal(source, target, -0.5f))
        assertRectEquals(target, ArtworkTransitionGeometry.interpolateDismissal(source, target, 1.5f))
    }

    @Test
    fun progressOutsideRangeIsClamped() {
        val source = Rect(10f, 20f, 110f, 220f)
        val target = Rect(210f, 120f, 410f, 520f)

        assertRectEquals(source, ArtworkTransitionGeometry.interpolate(source, target, -0.25f))
        assertRectEquals(target, ArtworkTransitionGeometry.interpolate(source, target, 1.25f))
    }

    @Test
    fun translatedDestinationBoundsReturnToStablePlayerCoordinates() {
        val measuredWhileSheetIsMoving = Rect(120f, 680f, 520f, 1080f)
        val parentTranslation = androidx.compose.ui.geometry.Offset(0f, 340f)

        assertRectEquals(
            Rect(120f, 340f, 520f, 740f),
            ArtworkTransitionGeometry.removeParentTranslation(measuredWhileSheetIsMoving, parentTranslation),
        )
    }

    @Test
    fun invalidOrEmptyBoundsAreRejected() {
        val valid = Rect(0f, 0f, 10f, 10f)
        val empty = Rect(0f, 0f, 0f, 10f)
        val nonFinite = Rect(Float.NaN, 0f, 10f, 10f)

        assertIllegalArgument { ArtworkTransitionGeometry.interpolate(empty, valid, 0.5f) }
        assertIllegalArgument { ArtworkTransitionGeometry.interpolate(valid, empty, 0.5f) }
        assertIllegalArgument { ArtworkTransitionGeometry.interpolate(nonFinite, valid, 0.5f) }
    }

    private fun assertRectEquals(expected: Rect, actual: Rect, tolerance: Float = 0.001f) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }
}
