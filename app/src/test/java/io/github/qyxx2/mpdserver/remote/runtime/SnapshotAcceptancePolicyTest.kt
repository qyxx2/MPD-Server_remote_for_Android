package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAcceptancePolicyTest {
    private val policy = SnapshotAcceptancePolicy()

    @Test fun rejectsOlderEpochSequenceAndRevisions() {
        val current = snapshot("e1", 10, libraryRevision = 4, playlistRevision = 6, stopGeneration = 3, queueRevision = 8, libraryGeneration = 5)
        assertEquals(SnapshotRejection.OLDER_SEQUENCE, policy.evaluate(current, 4, snapshot("e1", 9), 4))
        assertEquals(SnapshotRejection.REVISION_REGRESSION, policy.evaluate(current, 4, snapshot("e1", 11, libraryRevision = 3), 4))
        assertEquals(SnapshotRejection.GENERATION_REGRESSION, policy.evaluate(current, 4, snapshot("e1", 11, stopGeneration = 2), 4))
        assertEquals(SnapshotRejection.STALE_CONNECTION, policy.evaluate(current, 4, snapshot("e2", 1), 3))
        assertEquals(SnapshotRejection.EPOCH_CHANGE_WITHOUT_NEW_CONNECTION, policy.evaluate(current, 4, snapshot("e2", 1), 4))
        assertEquals(SnapshotRejection.ACCEPTED, policy.evaluate(current, 4, snapshot("e2", 1), 5))
        assertEquals(SnapshotRejection.GENERATION_REGRESSION, policy.evaluate(current, 4, snapshot("e1", 11, libraryGeneration = 4), 4))
    }

    @Test fun sameEpochReconnectCannotRegressSequenceOrRevisions() {
        val current = snapshot("e1", 10, libraryRevision = 4, queueRevision = 8, libraryGeneration = 5)
        assertEquals(SnapshotRejection.OLDER_SEQUENCE, policy.evaluate(current, 4, snapshot("e1", 9), 5))
        assertEquals(SnapshotRejection.REVISION_REGRESSION, policy.evaluate(current, 4, snapshot("e1", 10, queueRevision = 7), 5))
    }

    @Test fun storeRetainsLastKnownAsStaleAndRequiresAcceptedSnapshotForFreshness() {
        val store = SnapshotStore()
        assertEquals(SnapshotFreshness.UNKNOWN, store.state.value.freshness)
        assertFalse(store.state.value.controlsEligible)
        store.onConnectionState(1, RuntimeConnectionStatus.CONNECTING)
        assertEquals(SnapshotFreshness.UNKNOWN, store.state.value.freshness)
        assertTrue(store.accept(snapshot("e1", 1).wire, 1))
        assertEquals(SnapshotFreshness.FRESH, store.state.value.freshness)
        store.onConnectionState(2, RuntimeConnectionStatus.CONNECTING)
        assertEquals(SnapshotFreshness.STALE, store.state.value.freshness)
        assertFalse(store.state.value.controlsEligible)
        assertEquals("e1", store.state.value.snapshot?.epoch)
    }

    @Test fun httpReadCannotRestoreFreshnessBeforeRealtimeConnectionIsReady() {
        val store = SnapshotStore()
        store.onConnectionState(1, RuntimeConnectionStatus.CONNECTING)
        assertFalse(store.accept(snapshot("e1", 1).wire, 1, SnapshotSource.HTTP_READ))
        assertEquals(SnapshotFreshness.UNKNOWN, store.state.value.freshness)
        assertFalse(store.state.value.controlsEligible)
    }

    @Test fun lateConnectingStatusCannotDowngradeAnAcceptedRealtimeSnapshotInSameGeneration() {
        val store = SnapshotStore()
        store.onConnectionState(1, RuntimeConnectionStatus.CONNECTING)

        assertTrue(store.accept(snapshot("e1", 1).wire, 1, SnapshotSource.REALTIME_INITIAL))
        assertEquals(RuntimeConnectionStatus.CONNECTED, store.state.value.connectionStatus)
        assertEquals(SnapshotFreshness.FRESH, store.state.value.freshness)

        // RealtimeConnection emits the snapshot event before its Ready state; the state collector
        // can deliver the intermediate AwaitingSnapshot/CONNECTING status just after acceptance.
        store.onConnectionState(1, RuntimeConnectionStatus.CONNECTING)

        assertEquals(RuntimeConnectionStatus.CONNECTED, store.state.value.connectionStatus)
        assertEquals(SnapshotFreshness.FRESH, store.state.value.freshness)
        assertTrue(store.state.value.controlsEligible)
    }

    @Test fun doesNotInferActualPlaybackFromBusinessState() {
        val dto = snapshot("e1", 1).wire
        val store = SnapshotStore()
        store.onConnectionState(1, RuntimeConnectionStatus.CONNECTING)
        store.accept(dto, 1)
        assertEquals(dto.playbackObservation, store.state.value.snapshot?.wire?.playbackObservation)
        assertFalse(store.state.value.actualPlaybackConfirmed)
    }

    private fun snapshot(
        epoch: String,
        sequence: Long,
        libraryRevision: Long = 4,
        playlistRevision: Long = 6,
        stopGeneration: Long = 3,
        queueRevision: Long = 8,
        libraryGeneration: Long = 5,
    ): FullStateSnapshot = FullStateSnapshot.from(
        StateSnapshotDto(
            epoch, sequence, "2026-10-24T12:00:00Z", stopGeneration,
            StateRevisionsDto(libraryRevision, playlistRevision),
            Json.parseToJsonElement("""{"state":"PLAYING"}"""),
            null,
            Json.parseToJsonElement("""{"revision":$queueRevision,"items":[]}"""),
            Json.parseToJsonElement("""{}"""),
            Json.parseToJsonElement("""{"actual_state":null,"freshness":"unknown"}"""),
            Json.parseToJsonElement("""{}"""),
            Json.parseToJsonElement("""{}"""),
            Json.parseToJsonElement("""{"generation":$libraryGeneration}"""),
        )
    )
}
