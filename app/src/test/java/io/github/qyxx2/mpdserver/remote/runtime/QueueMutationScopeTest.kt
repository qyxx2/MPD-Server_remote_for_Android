package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class QueueMutationScopeTest {
    @Test
    fun staleReadDoesNotInvalidateRetryWhenTheKnownQueueScopeIsUnchanged() {
        val fresh = PlayerTestFixtures.state()
        val staleSameScope = fresh.copy(freshness = SnapshotFreshness.STALE)

        assertNotNull(QueueMutationScope.fingerprint(fresh))
        assertEquals(QueueMutationScope.fingerprint(fresh), QueueMutationScope.fingerprint(staleSameScope))
    }

    @Test
    fun epochGenerationRevisionAndOccurrenceChangesInvalidateRetryScope() {
        val baseline = PlayerTestFixtures.state()
        val revisionChanged = PlayerTestFixtures.state(
            queue = PlayerTestFixtures.queueJson.replace("\"revision\":17", "\"revision\":18"),
        )
        val epochChangedSnapshot = baseline.snapshot!!.wire.copy(epoch = "epoch-2")
        val epochChanged = baseline.copy(snapshot = FullStateSnapshot.from(epochChangedSnapshot))
        val stopChangedSnapshot = baseline.snapshot.wire.copy(explicitStopGeneration = 1)
        val stopChanged = baseline.copy(snapshot = FullStateSnapshot.from(stopChangedSnapshot))
        val contextChanged = PlayerTestFixtures.state(
            queue = PlayerTestFixtures.queueJson.replace("ctx-1", "ctx-2"),
        )
        val generationChanged = baseline.copy(
            connectionGeneration = baseline.connectionGeneration + 1,
            snapshotConnectionGeneration = baseline.snapshotConnectionGeneration,
        )
        val occurrenceChanged = PlayerTestFixtures.state(
            queue = PlayerTestFixtures.queueJson.replace("occurrence-1", "occurrence-other"),
        )

        val scope = requireNotNull(QueueMutationScope.fingerprint(baseline))
        assertEquals(false, scope == QueueMutationScope.fingerprint(revisionChanged))
        assertEquals(false, scope == QueueMutationScope.fingerprint(epochChanged))
        assertEquals(false, scope == QueueMutationScope.fingerprint(stopChanged))
        assertEquals(false, scope == QueueMutationScope.fingerprint(contextChanged))
        assertEquals(false, scope == QueueMutationScope.fingerprint(generationChanged))
        assertEquals(false, scope == QueueMutationScope.fingerprint(occurrenceChanged))
    }

    @Test
    fun retryScopeIsUnavailableWhenDisconnectedOrSnapshotBelongsToAnotherGeneration() {
        val baseline = PlayerTestFixtures.state()
        assertNull(
            QueueMutationScope.fingerprint(
                baseline.copy(connectionStatus = RuntimeConnectionStatus.DISCONNECTED),
            ),
        )
        assertNull(
            QueueMutationScope.fingerprint(
                baseline.copy(snapshotConnectionGeneration = baseline.connectionGeneration - 1),
            ),
        )
        assertNull(
            QueueMutationScope.fingerprint(
                baseline.copy(freshness = SnapshotFreshness.UNKNOWN),
            ),
        )
    }
}
