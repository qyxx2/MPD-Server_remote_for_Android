package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.dto.ObservationFreshnessDto
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCanonicalTuple
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapabilityStatus
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVolumeSliderDraftTest {
    @Test
    fun keepsPreviewLocalAndProducesOneRoundedTargetOnlyForTheOriginalFreshTuple() {
        val tuple = freshTuple()
        val first = HardwareVolumeSliderDraft().preview(71.4f, tuple)
        val second = first.preview(72.6f, tuple)

        assertEquals(72.6f, second.previewPercent!!, 0.001f)
        assertEquals("a drag only creates one absolute integer target at release", 73, second.targetForRelease(tuple, canWrite = true))
        assertNull("stale capability cannot release a target", second.targetForRelease(tuple, canWrite = false))
    }

    @Test
    fun canonicalVolumeChangeInvalidatesDraftAndCannotBeCommitted() {
        val original = freshTuple()
        val changed = original.copy(volumePercent = 79)
        val draft = HardwareVolumeSliderDraft().preview(60f, original)

        val invalidated = draft.invalidateIfCanonicalChanged(changed)
        assertTrue(invalidated.invalidated)
        assertNull(invalidated.previewPercent)
        assertNull(invalidated.targetForRelease(changed, canWrite = true))
        assertFalse(invalidated == draft)
    }

    @Test
    fun statusFreshnessAndConnectionGenerationChangesInvalidateDraft() {
        val original = freshTuple()
        val draft = HardwareVolumeSliderDraft().preview(60f, original)
        val changedTuples = listOf(
            original.copy(status = HardwareVolumeCapabilityStatus.UNAVAILABLE),
            original.copy(observationFreshness = ObservationFreshnessDto.stale),
            original.copy(snapshotFreshness = SnapshotFreshness.STALE),
            original.copy(connectionGeneration = 2, snapshotConnectionGeneration = 2),
            original.copy(connectionStatus = RuntimeConnectionStatus.DISCONNECTED),
        )
        changedTuples.forEach { changed ->
            assertTrue("changed tuple must invalidate the draft: $changed", draft.invalidateIfCanonicalChanged(changed).invalidated)
        }
    }

    @Test
    fun unavailableBaselineCannotInventATarget() {
        val draft = HardwareVolumeSliderDraft().preview(50f, null)
        assertNull(draft.previewPercent)
        assertNull(draft.targetForRelease(null, canWrite = true))
    }

    private fun freshTuple() = HardwareVolumeCanonicalTuple(
        epoch = "epoch-a",
        connectionGeneration = 1,
        snapshotConnectionGeneration = 1,
        connectionStatus = RuntimeConnectionStatus.CONNECTED,
        snapshotFreshness = SnapshotFreshness.FRESH,
        status = HardwareVolumeCapabilityStatus.AVAILABLE,
        volumePercent = 80,
        stale = false,
        observationFreshness = ObservationFreshnessDto.fresh,
    )
}
