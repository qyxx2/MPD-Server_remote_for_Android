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
    fun progressOutsideRangeIsClamped() {
        val source = Rect(10f, 20f, 110f, 220f)
        val target = Rect(210f, 120f, 410f, 520f)

        assertRectEquals(source, ArtworkTransitionGeometry.interpolate(source, target, -0.25f))
        assertRectEquals(target, ArtworkTransitionGeometry.interpolate(source, target, 1.25f))
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

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }
}
