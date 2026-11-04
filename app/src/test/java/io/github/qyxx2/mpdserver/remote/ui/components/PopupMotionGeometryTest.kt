package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupMotionGeometryTest {
    @Test
    fun triggerCenterMapsToPopupTransformOrigin() {
        val trigger = Rect(120f, 240f, 180f, 300f)
        val popup = Rect(100f, 200f, 300f, 400f)
        val origin = PopupMotionSpec.transformOrigin(trigger, popup)
        assertEquals(0.25f, origin.pivotFractionX, 0.0001f)
        assertEquals(0.35f, origin.pivotFractionY, 0.0001f)
    }

    @Test
    fun triggerAboveAndBelowPopupAreClampedToSurfaceEdges() {
        val popup = Rect(100f, 100f, 300f, 300f)
        val above = PopupMotionSpec.transformOrigin(Rect(140f, 0f, 180f, 40f), popup)
        val below = PopupMotionSpec.transformOrigin(Rect(140f, 360f, 180f, 400f), popup)
        assertEquals(0f, above.pivotFractionY, 0.0001f)
        assertEquals(1f, below.pivotFractionY, 0.0001f)
    }

    @Test
    fun triggerOutsideHorizontalBoundsMapsToNearestEdge() {
        val popup = Rect(100f, 100f, 300f, 300f)
        val left = PopupMotionSpec.transformOrigin(Rect(0f, 140f, 20f, 180f), popup)
        val right = PopupMotionSpec.transformOrigin(Rect(380f, 140f, 420f, 180f), popup)
        assertEquals(0f, left.pivotFractionX, 0.0001f)
        assertEquals(1f, right.pivotFractionX, 0.0001f)
    }

    @Test
    fun missingTriggerUsesMeasuredOwnerBounds() {
        val owner = Rect(40f, 80f, 140f, 180f)
        val popup = Rect(0f, 0f, 200f, 240f)
        val resolved = PopupMotionSpec.resolveAnchorBounds(null, owner)
        val origin = PopupMotionSpec.transformOrigin(resolved, popup)
        assertEquals(owner, resolved)
        assertEquals(0.45f, origin.pivotFractionX, 0.0001f)
        assertEquals(0.5416667f, origin.pivotFractionY, 0.0001f)
    }
}
