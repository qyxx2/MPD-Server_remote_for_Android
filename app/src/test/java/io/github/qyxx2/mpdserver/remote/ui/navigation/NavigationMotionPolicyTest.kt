package io.github.qyxx2.mpdserver.remote.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMotionPolicyTest {
    @Test
    fun ordinaryNavigationUsesQuarterWidthTravelAndTwoHundredMillis() {
        assertEquals(200, NavigationMotionPolicy.durationMillis)
        assertEquals(0.25f, NavigationMotionPolicy.travelFraction, 0f)
    }

    @Test
    fun forwardAndPopOffsetsUseOppositeQuarterWidthDirections() {
        assertEquals(100, NavigationMotionPolicy.forwardEnterOffset(400))
        assertEquals(-100, NavigationMotionPolicy.forwardExitOffset(400))
        assertEquals(-100, NavigationMotionPolicy.popEnterOffset(400))
        assertEquals(100, NavigationMotionPolicy.popExitOffset(400))
    }

    @Test
    fun offsetRoundingIsDeterministicForOddAndZeroWidths() {
        assertEquals(25, NavigationMotionPolicy.forwardEnterOffset(101))
        assertEquals(-25, NavigationMotionPolicy.forwardExitOffset(101))
        assertEquals(-25, NavigationMotionPolicy.popEnterOffset(101))
        assertEquals(25, NavigationMotionPolicy.popExitOffset(101))
        assertEquals(0, NavigationMotionPolicy.forwardEnterOffset(0))
        assertEquals(0, NavigationMotionPolicy.popExitOffset(0))
    }
}
