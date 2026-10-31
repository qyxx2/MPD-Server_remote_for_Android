package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/** Permissive right-swipe classifier: tracks the gesture path, not only its final straight-line angle. */
internal object LibraryBackGesturePolicy {
    fun shouldNavigateBack(
        positiveHorizontalPx: Float,
        negativeHorizontalPx: Float,
        verticalTravelPx: Float,
        netHorizontalPx: Float,
        thresholdPx: Float,
    ): Boolean {
        if (!thresholdPx.isFinite() || thresholdPx <= 0f) return false
        val horizontalProgress = positiveHorizontalPx - negativeHorizontalPx * 0.65f
        if (horizontalProgress < thresholdPx || netHorizontalPx < thresholdPx * 0.80f) return false
        val verticalAllowance = max(thresholdPx * 1.5f, horizontalProgress * 1.25f)
        return verticalTravelPx <= verticalAllowance
    }
}

/**
 * Accepts diagonal and curved rightward swipes from anywhere in the page. Vertical list scrolling
 * remains independent: a gesture must accumulate meaningful net horizontal progress before it
 * consumes pointer changes. Android's system edge-back gesture remains handled by BackHandler.
 */
internal fun Modifier.libraryBackGesture(onBack: () -> Unit): Modifier = pointerInput(onBack) {
    val thresholdPx = 88.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        var previous = down.position
        var positiveHorizontalPx = 0f
        var negativeHorizontalPx = 0f
        var verticalTravelPx = 0f
        var recognized = false
        var navigateBack = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) break
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            val current = change.position
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            if (dx >= 0f) positiveHorizontalPx += dx else negativeHorizontalPx += abs(dx)
            verticalTravelPx += abs(dy)

            if (!recognized) {
                recognized = LibraryBackGesturePolicy.shouldNavigateBack(
                    positiveHorizontalPx = positiveHorizontalPx,
                    negativeHorizontalPx = negativeHorizontalPx,
                    verticalTravelPx = verticalTravelPx,
                    netHorizontalPx = current.x - down.position.x,
                    thresholdPx = thresholdPx,
                )
            }
            if (recognized) change.consume()
            previous = current

            if (!change.pressed) {
                navigateBack = recognized
                break
            }
        }
        if (navigateBack) onBack()
    }
}
