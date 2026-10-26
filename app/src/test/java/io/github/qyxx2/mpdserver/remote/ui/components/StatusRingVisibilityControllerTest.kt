package io.github.qyxx2.mpdserver.remote.ui.components

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRingVisibilityControllerTest {
    private val origin = ServerOrigin.parse("http://localhost:18000")
    private val controller = StatusRingVisibilityController()

    @Test
    fun noActivitiesDoNotShowAPermanentIdleRing() {
        val state = controller.update(emptyList(), nowMillis = 0L)

        assertFalse(state.visible)
        assertNull(state.hideAtMillis)
    }

    @Test
    fun activeActivitiesStayVisibleWithoutStartingTheHideTimer() {
        val state = controller.update(listOf(activity("a", MutationStatus.PENDING)), nowMillis = 100L)

        assertTrue(state.visible)
        assertNull(state.hideAtMillis)
        assertTrue(controller.onTimer(nowMillis = 100_000L).visible)
    }

    @Test
    fun completedActivityRemainsVisibleForFiveSecondsAndHidesAtTheDeadline() {
        controller.update(emptyList(), nowMillis = 0L)
        controller.update(listOf(activity("a", MutationStatus.PENDING)), nowMillis = 100L)

        val completed = controller.update(listOf(activity("a", MutationStatus.SUCCESS)), nowMillis = 1_000L)

        assertTrue(completed.visible)
        assertEquals(6_000L, completed.hideAtMillis)
        assertTrue(controller.onTimer(nowMillis = 5_999L).visible)
        assertFalse(controller.onTimer(nowMillis = 6_000L).visible)
    }

    @Test
    fun newActivityCancelsAnExistingHideTimerAndItsCompletionStartsANewFiveSeconds() {
        controller.update(emptyList(), nowMillis = 0L)
        controller.update(listOf(activity("a", MutationStatus.SUCCESS)), nowMillis = 100L)
        assertEquals(5_100L, controller.current(nowMillis = 200L).hideAtMillis)

        val inProgress = controller.update(
            listOf(activity("a", MutationStatus.SUCCESS), activity("b", MutationStatus.SYNCING)),
            nowMillis = 3_000L,
        )
        assertTrue(inProgress.visible)
        assertNull(inProgress.hideAtMillis)

        val completed = controller.update(
            listOf(activity("a", MutationStatus.SUCCESS), activity("b", MutationStatus.INFO)),
            nowMillis = 4_000L,
        )
        assertEquals(9_000L, completed.hideAtMillis)
        assertTrue(controller.onTimer(nowMillis = 8_999L).visible)
        assertFalse(controller.onTimer(nowMillis = 9_000L).visible)
    }

    @Test
    fun unresolvedUnknownAndFailedActivitiesNeverExpireByTimer() {
        controller.update(emptyList(), nowMillis = 0L)
        val unknown = controller.update(listOf(activity("a", MutationStatus.UNKNOWN)), nowMillis = 100L)
        assertTrue(unknown.visible)
        assertNull(unknown.hideAtMillis)
        assertTrue(controller.onTimer(nowMillis = 100_000L).visible)

        val failed = controller.update(listOf(activity("a", MutationStatus.FAILED)), nowMillis = 100_001L)
        assertTrue(failed.visible)
        assertNull(failed.hideAtMillis)
        assertTrue(controller.onTimer(nowMillis = 1_000_000L).visible)
    }

    @Test
    fun previouslyExistingCompletedHistoryDoesNotReappearOnControllerInitialization() {
        val state = controller.update(listOf(activity("old", MutationStatus.SUCCESS)), nowMillis = 50_000L)

        assertFalse(state.visible)
        assertNull(state.hideAtMillis)
    }

    @Test
    fun anActivityRemovedWithoutATerminalSnapshotGetsTheSameFiveSecondGracePeriod() {
        controller.update(emptyList(), nowMillis = 0L)
        controller.update(listOf(activity("a", MutationStatus.PENDING)), nowMillis = 100L)

        val removed = controller.update(emptyList(), nowMillis = 1_000L)

        assertEquals(6_000L, removed.hideAtMillis)
        assertTrue(controller.onTimer(nowMillis = 5_999L).visible)
        assertFalse(controller.onTimer(nowMillis = 6_000L).visible)
    }

    private fun activity(id: String, status: MutationStatus) = OperationActivity(
        intentId = id,
        label = "Test activity",
        status = status,
        idempotencyKey = null,
        origin = origin,
    )
}
