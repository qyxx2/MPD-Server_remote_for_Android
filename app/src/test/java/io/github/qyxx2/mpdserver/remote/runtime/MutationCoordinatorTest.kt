package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiErrorDto
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MutationCoordinatorTest {
    @Test fun retriesSameIntentWithSameKeyMethodPathAndPayload() {
        val sent = mutableListOf<MutationRequest>()
        val coordinator = coordinator { request -> sent += request; HttpResult.Failed(HttpFailure.Transport(IOException("offline"))) }
        val intent = MutationIntent("playback-pause", "POST", "/api/playback/pause", "{\"target\":\"current\"}")
        coordinator.submit(intent)
        coordinator.retryUnknown(intent.id)
        assertEquals(2, sent.size)
        assertEquals(sent[0].idempotencyKey, sent[1].idempotencyKey)
        assertEquals(sent[0].method, sent[1].method)
        assertEquals(sent[0].path, sent[1].path)
        assertEquals(sent[0].payload, sent[1].payload)
    }

    @Test fun transportAmbiguityIsUnknownAndNeverAutomaticallyReplayed() {
        var calls = 0
        val coordinator = coordinator { calls++; HttpResult.Failed(HttpFailure.Transport(IOException("timeout"))) }
        val result = coordinator.submit(intent())
        assertTrue(result is MutationOutcome.Unknown)
        assertEquals(1, calls)
        assertEquals(1, coordinator.activities.value.size)
        assertEquals(MutationStatus.UNKNOWN, coordinator.activities.value.single().status)
    }

    @Test fun explicitBusinessHttpFailureIsFailedNotUnknown() {
        val coordinator = coordinator { HttpResult.HttpError(409, "application/json", """{"error":{"code":"conflict","message":"not allowed"}}""".toByteArray()) }
        assertTrue(coordinator.submit(intent()) is MutationOutcome.Failed)
        assertEquals(MutationStatus.FAILED, coordinator.activities.value.single().status)
    }

    @Test fun noContentIsSuccessWithoutAttemptingToDecodeBody() {
        val coordinator = coordinator { HttpResult.NoContent() }
        assertTrue(coordinator.submit(intent()) is MutationOutcome.Confirmed)
        assertEquals(MutationStatus.SUCCESS, coordinator.activities.value.single().status)
    }

    @Test fun collectionCreationUsesTheDocumentedIdempotencyException() {
        var captured: MutationRequest? = null
        val coordinator = coordinator { request -> captured = request; HttpResult.NoContent() }
        coordinator.submit(MutationIntent("collection-create", "POST", "/api/library/collections", "{}"))
        assertNull(captured?.idempotencyKey)
    }

    @Test fun confirmedWriteRemainsConfirmedWhenFollowupReadFails() {
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = { HttpResult.NoContent() },
            synchronize = { intentId -> throw IOException("read failed") },
            keyFactory = { "stable-key" },
        )
        assertTrue(coordinator.submit(intent()) is MutationOutcome.Confirmed)
        val activity = coordinator.activities.value.single()
        assertEquals(MutationStatus.SUCCESS, activity.status)
        assertEquals("read failed", activity.syncError)
    }

    @Test fun originChangeBlocksRetryAndDoesNotCreateNewKeyOrMigrateIntent() {
        val keys = mutableListOf<String>()
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = { HttpResult.Failed(HttpFailure.Transport(IOException("timeout"))) },
            keyFactory = { "key-${keys.size}".also(keys::add) },
        )
        val id = intent()
        coordinator.submit(id)
        coordinator.changeOrigin(ServerOrigin.parse("https://other.example:18443"))
        assertEquals(MutationOutcome.BlockedOriginChange, coordinator.retryUnknown(id.id))
        assertEquals(listOf("key-0"), keys)
    }

    @Test fun unknownMutationIsNotPersistedOrReplayedByNewCoordinator() {
        var calls = 0
        val first = coordinator { calls++; HttpResult.Failed(HttpFailure.Transport(IOException("timeout"))) }
        first.submit(intent())
        val second = coordinator { calls++; HttpResult.NoContent() }
        assertTrue(second.activities.value.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun pendingDuplicateIntentIsNotSentTwice() {
        val coordinator = coordinator { HttpResult.NoContent() }
        val intent = intent()
        coordinator.beginPendingForTesting(intent)
        assertEquals(MutationOutcome.DuplicatePending, coordinator.submit(intent))
    }

    @Test fun conflictingPayloadCannotHideBehindPendingDuplicateDetection() {
        val coordinator = coordinator { HttpResult.NoContent() }
        coordinator.beginPendingForTesting(intent())
        val result = coordinator.submit(intent().copy(payload = """{"different":true}"""))
        assertTrue(result is MutationOutcome.Failed)
    }

    @Test fun serverErrorIsUnknownBecauseCommitOutcomeIsAmbiguous() {
        val coordinator = coordinator { HttpResult.HttpError(502, "text/plain", "upstream unavailable".toByteArray()) }
        assertTrue(coordinator.submit(intent()) is MutationOutcome.Unknown)
        assertEquals(MutationStatus.UNKNOWN, coordinator.activities.value.single().status)
    }

    @Test fun malformedClientErrorRemainsAnExplicitFailure() {
        val coordinator = coordinator { HttpResult.HttpError(400, "application/json", "not-json".toByteArray()) }
        assertTrue(coordinator.submit(intent()) is MutationOutcome.Failed)
        assertEquals(MutationStatus.FAILED, coordinator.activities.value.single().status)
    }

    private fun coordinator(send: (MutationRequest) -> HttpResult) = MutationCoordinator(
        origin = ServerOrigin.parse("http://music.example:18000"),
        send = send,
        keyFactory = { "stable-key" },
    )

    private fun intent(id: String = "intent-1") = MutationIntent(id, "POST", "/api/playback/pause", "{}")
}
