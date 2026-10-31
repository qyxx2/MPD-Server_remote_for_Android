package io.github.qyxx2.mpdserver.remote.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackGesturePolicyTest {
    @Test
    fun diagonalRightSwipeIsAccepted() {
        assertTrue(
            LibraryBackGesturePolicy.shouldNavigateBack(
                positiveHorizontalPx = 140f,
                negativeHorizontalPx = 8f,
                verticalTravelPx = 96f,
                netHorizontalPx = 132f,
                thresholdPx = 88f,
            ),
        )
    }

    @Test
    fun curvedRightSwipeIsAcceptedWhenItMakesNetRightwardProgress() {
        assertTrue(
            LibraryBackGesturePolicy.shouldNavigateBack(
                positiveHorizontalPx = 182f,
                negativeHorizontalPx = 28f,
                verticalTravelPx = 105f,
                netHorizontalPx = 154f,
                thresholdPx = 88f,
            ),
        )
    }

    @Test
    fun mostlyVerticalScrollIsNotMistakenForBackGesture() {
        assertFalse(
            LibraryBackGesturePolicy.shouldNavigateBack(
                positiveHorizontalPx = 35f,
                negativeHorizontalPx = 0f,
                verticalTravelPx = 220f,
                netHorizontalPx = 35f,
                thresholdPx = 88f,
            ),
        )
    }

    @Test
    fun shortOrNetLeftwardGesturesAreRejected() {
        assertFalse(
            LibraryBackGesturePolicy.shouldNavigateBack(
                positiveHorizontalPx = 60f,
                negativeHorizontalPx = 0f,
                verticalTravelPx = 12f,
                netHorizontalPx = 60f,
                thresholdPx = 88f,
            ),
        )
        assertFalse(
            LibraryBackGesturePolicy.shouldNavigateBack(
                positiveHorizontalPx = 160f,
                negativeHorizontalPx = 100f,
                verticalTravelPx = 40f,
                netHorizontalPx = 60f,
                thresholdPx = 88f,
            ),
        )
    }
}
