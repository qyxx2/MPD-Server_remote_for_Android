package io.github.qyxx2.mpdserver.remote.ui.components

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPresentationTest {
    private val origin = ServerOrigin.parse("http://localhost:18000")

    @Test fun disconnectedUnknownAndStaleSnapshotsNeverLookSuccessful() {
        assertEquals(ActivityVisualState.DISCONNECTED, project(SnapshotStoreState(), emptyList()).visualState)
        assertEquals(
            ActivityVisualState.UNKNOWN,
            project(SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED), emptyList()).visualState,
        )
        assertEquals(
            ActivityVisualState.STALE,
            project(SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED, freshness = SnapshotFreshness.STALE), emptyList()).visualState,
        )
        assertFalse(project(SnapshotStoreState(), emptyList()).isConfirmedAvailable)
    }

    @Test fun connectingStateIsNotMisreportedAsDisconnected() {
        val result = project(
            SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTING, freshness = SnapshotFreshness.UNKNOWN),
            emptyList(),
        )
        assertEquals(ActivityVisualState.CONNECTING, result.visualState)
        assertEquals("正在连接", result.statusLabel)
        assertEquals("正在连接", result.connectionLabel)
    }

    @Test fun unresolvedActivityTakesPriorityOverConnectionAndRecentSuccess() {
        val snapshot = SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED, freshness = SnapshotFreshness.FRESH)
        assertEquals(ActivityVisualState.UNKNOWN, project(snapshot, listOf(activity(MutationStatus.SUCCESS), activity(MutationStatus.UNKNOWN))).visualState)
        assertEquals(ActivityVisualState.FAILED, project(snapshot, listOf(activity(MutationStatus.FAILED))).visualState)
        assertEquals(ActivityVisualState.SYNCING, project(snapshot, listOf(activity(MutationStatus.SYNCING))).visualState)
        assertEquals(ActivityVisualState.PENDING, project(snapshot, listOf(activity(MutationStatus.PENDING))).visualState)
    }

    @Test fun connectionFreshnessHasPriorityOverRetiredSuccessInfoAndIdle() {
        val disconnected = SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.DISCONNECTED, freshness = SnapshotFreshness.FRESH)
        assertEquals(ActivityVisualState.DISCONNECTED, project(disconnected, listOf(activity(MutationStatus.SUCCESS))).visualState)
        val stale = SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED, freshness = SnapshotFreshness.STALE)
        assertEquals(ActivityVisualState.STALE, project(stale, listOf(activity(MutationStatus.INFO))).visualState)
        val fresh = SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED, freshness = SnapshotFreshness.FRESH)
        assertEquals(ActivityVisualState.SUCCESS, project(fresh, listOf(activity(MutationStatus.SUCCESS))).visualState)
        assertEquals(ActivityVisualState.INFO, project(fresh, listOf(activity(MutationStatus.INFO))).visualState)
        assertEquals(ActivityVisualState.IDLE, project(fresh, emptyList()).visualState)
    }

    @Test fun retryIsOnlyOfferedForUnknownIntentOnCurrentOriginWithConfirmedWritableSnapshot() {
        val unknown = activity(MutationStatus.UNKNOWN)
        val disconnected = project(
            SnapshotStoreState(),
            listOf(unknown),
        )
        assertFalse(disconnected.activities.single().canRetry)

        val connectedButWithoutCanonicalSnapshot = ActivityPresentation.from(
            SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED, freshness = SnapshotFreshness.FRESH),
            listOf(unknown),
            currentOrigin = origin,
        )
        assertFalse(connectedButWithoutCanonicalSnapshot.activities.single().canRetry)
    }

    @Test fun unknownRetryRemainsAvailableForCurrentOriginWhenReadIsStaleButRuntimeIsWritable() {
        val staleConnected = PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE)
        val result = ActivityPresentation.from(staleConnected, listOf(activity(MutationStatus.UNKNOWN)), currentOrigin = origin)
        assertTrue(result.activities.single().canRetry)

        val disconnected = ActivityPresentation.from(
            staleConnected.copy(connectionStatus = RuntimeConnectionStatus.DISCONNECTED),
            listOf(activity(MutationStatus.UNKNOWN)),
            currentOrigin = origin,
        )
        assertFalse(disconnected.activities.single().canRetry)

        val differentOrigin = ActivityPresentation.from(
            staleConnected,
            listOf(activity(MutationStatus.UNKNOWN)),
            currentOrigin = ServerOrigin.parse("http://other.example:18000"),
        )
        assertFalse(differentOrigin.activities.single().canRetry)
    }

    @Test fun playerUnknownRetryRequiresTheMatchingUnchangedIntentIdentity() {
        val state = PlayerTestFixtures.state()
        val playerIntent = activity(MutationStatus.UNKNOWN).copy(intentId = "player:seek-1")
        val withoutIdentityPermission = ActivityPresentation.from(
            state,
            listOf(playerIntent),
            currentOrigin = origin,
        )
        assertFalse(withoutIdentityPermission.activities.single().canRetry)

        val withMatchingIdentity = ActivityPresentation.from(
            state,
            listOf(playerIntent),
            currentOrigin = origin,
            retryableIntentIds = setOf("player:seek-1"),
        )
        assertTrue(withMatchingIdentity.activities.single().canRetry)
        val staleButWritable = ActivityPresentation.from(
            PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE),
            listOf(playerIntent),
            currentOrigin = origin,
            retryableIntentIds = setOf("player:seek-1"),
        )
        assertTrue(staleButWritable.activities.single().canRetry)
    }

    @Test fun activityRowsRetainExistingSharedOwnerDataAndReadableFallbacks() {
        val result = project(
            SnapshotStoreState(connectionStatus = RuntimeConnectionStatus.CONNECTED, freshness = SnapshotFreshness.FRESH, readError = "snapshot error"),
            listOf(activity(MutationStatus.UNKNOWN, label = "", message = "write outcome unknown", syncError = "readback failed")),
        )
        assertEquals("结果未知", result.statusLabel)
        assertEquals("snapshot error", result.readError)
        assertEquals("操作", result.activities.single().title)
        assertEquals("write outcome unknown", result.activities.single().message)
        assertEquals("readback failed", result.activities.single().syncError)
        assertTrue(result.activities.single().statusLabel.isNotBlank())
    }

    private fun activity(status: MutationStatus, label: String = "测试操作", message: String? = null, syncError: String? = null) =
        OperationActivity("intent-1", label, status, message, syncError, "key-1", origin)

    private fun project(state: SnapshotStoreState, activities: List<OperationActivity>) =
        ActivityPresentation.from(state, activities)
}
