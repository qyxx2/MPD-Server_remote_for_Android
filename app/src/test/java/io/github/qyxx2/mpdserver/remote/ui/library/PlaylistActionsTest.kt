package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto
import io.github.qyxx2.mpdserver.remote.runtime.*
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PlaylistActionsTest {
    private class Harness {
        var state = PlayerTestFixtures.state()
        var nextId = 0
        val activities = MutableStateFlow<List<OperationActivity>>(emptyList())
        val submitted = mutableListOf<MutationIntent>()
        val retried = mutableListOf<String>()
        val gateway = PlaybackMutationGateway(
            stateProvider = { state },
            activities = activities,
            submitter = { submitted += it; MutationOutcome.Submitted },
            retryUnknown = { retried += it; MutationOutcome.Submitted },
            canRetryUnknown = { id -> activities.value.any { it.intentId == id && it.status == MutationStatus.UNKNOWN } },
            idFactory = { "playlist-" + (++nextId) },
        )
        fun actions(refresh: suspend (PlaylistMutationTicket) -> Boolean = { true }) =
            PlaylistActions(gateway, refreshResources = refresh)
        fun baseline(system: Boolean = false) = actions().captureBaseline(
            PlaylistDto("playlist #1", "Mix", "", "", system, listOf("a", "b")),
        )!!
        fun activity(id: String, status: MutationStatus, error: String? = null) = OperationActivity(
            id, "playlist mutation", status, syncError = error, idempotencyKey = "key",
            origin = ServerOrigin.parse("http://music.example:18000"),
        )
    }

    @Test fun crudUsesExactMethodsPathsAndPayloads() {
        val h = Harness(); val a = h.actions(); val b = h.baseline()
        assertEquals(MutationOutcome.Submitted, a.create("  My Mix  "))
        assertEquals(MutationOutcome.Submitted, a.rename(b, " Renamed "))
        assertEquals(MutationOutcome.Submitted, a.delete(b))
        assertEquals(listOf("POST", "PATCH", "DELETE"), h.submitted.map { it.method })
        assertEquals(listOf("/api/playlists", "/api/playlists/playlist%20%231", "/api/playlists/playlist%20%231"), h.submitted.map { it.path })
        assertEquals("{\"name\":\"My Mix\"}", h.submitted[0].payload)
        assertEquals("{\"name\":\"Renamed\"}", h.submitted[1].payload)
        assertNull(h.submitted[2].payload)
    }

    @Test fun systemPlaylistRestrictsOnlyRenameAndDelete() {
        val h = Harness(); val a = h.actions(); val b = h.baseline(system = true)
        assertNull(a.rename(b, "new")); assertNull(a.delete(b))
        assertEquals(MutationOutcome.Submitted, a.add(b, "c"))
        assertEquals(MutationOutcome.Submitted, a.remove(b, "a"))
        assertEquals(MutationOutcome.Submitted, a.reorder(b, listOf("b", "a")))
        assertEquals(listOf("POST", "DELETE", "PUT"), h.submitted.map { it.method })
    }

    @Test fun memberPathsPayloadsAndFullOrderAreExact() {
        val h = Harness(); val a = h.actions(); val b = h.baseline()
        assertEquals(MutationOutcome.Submitted, a.add(b, "song/1"))
        assertEquals(MutationOutcome.Submitted, a.remove(b, "b"))
        assertEquals(MutationOutcome.Submitted, a.reorder(b, listOf("b", "a")))
        assertEquals(listOf(
            "/api/playlists/playlist%20%231/songs",
            "/api/playlists/playlist%20%231/songs/b",
            "/api/playlists/playlist%20%231/songs/order",
        ), h.submitted.map { it.path })
        assertEquals("{\"song_id\":\"song/1\"}", h.submitted[0].payload)
        assertNull(h.submitted[1].payload)
        val order = JsonConfiguration.json.parseToJsonElement(h.submitted[2].payload!!).jsonObject
        assertEquals(listOf("b", "a"), order.getValue("ordered_song_ids").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun duplicateMemberIsSentToServerSoItsCanonicalConflictIsPreserved() {
        val h = Harness(); val a = h.actions(); val b = h.baseline()
        assertEquals(MutationOutcome.Submitted, a.add(b, "a"))
        assertEquals("POST", h.submitted.single().method)
        assertEquals("/api/playlists/playlist%20%231/songs", h.submitted.single().path)
        assertEquals("{\"song_id\":\"a\"}", h.submitted.single().payload)
    }

    @Test fun blankNamesInvalidMembershipAndPartialOrDuplicateOrdersAreRejected() {
        val h = Harness(); val a = h.actions(); val b = h.baseline()
        assertNull(a.create("  ")); assertNull(a.rename(b, " "))
        assertNull(a.add(b, " ")); assertNull(a.remove(b, "missing"))
        assertNull(a.reorder(b, listOf("a"))); assertNull(a.reorder(b, listOf("a", "a")))
        assertTrue(h.submitted.isEmpty())
    }

    @Test fun staleResourceAndChangedSnapshotIdentityBlockWrites() {
        val h = Harness(); val a = h.actions(); val b = h.baseline()
        assertNull(a.add(b, "c", resourceFresh = false))
        assertNull(a.add(b, "c", membersConsistent = false))
        h.state = h.state.copy(connectionGeneration = h.state.connectionGeneration + 1)
        assertNull(a.rename(b, "new"))
        assertTrue(h.submitted.isEmpty())
    }

    @Test fun partialReadbackFailureStillRefreshesVisibleServerResourceState() = runBlocking {
        val h = Harness()
        val a = h.actions { false }
        assertEquals(MutationOutcome.Submitted, a.add(h.baseline(), "c"))
        val ticket = a.lastAction.value!!
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.SUCCESS))
        assertFalse(a.awaitAndRefresh(ticket))
        assertEquals(1L, a.refreshGeneration.value)
        assertNull(a.lastReadback.value)
    }

    @Test fun serverNotFoundFailureIsPreservedAndDoesNotTriggerReadback() = runBlocking {
        val h = Harness(); var readbacks = 0
        val a = h.actions { readbacks++; true }
        assertEquals(MutationOutcome.Submitted, a.delete(h.baseline()))
        val ticket = a.lastAction.value!!
        h.activities.value = listOf(
            OperationActivity(
                ticket.intentId,
                "delete playlist",
                MutationStatus.FAILED,
                message = "404 PLAYLIST_NOT_FOUND",
                idempotencyKey = "key",
                origin = ServerOrigin.parse("http://music.example:18000"),
            ),
        )
        assertFalse(a.awaitAndRefresh(ticket))
        assertEquals(0, readbacks)
        assertEquals("404 PLAYLIST_NOT_FOUND", h.activities.value.single().message)
    }

    @Test fun duplicateAndMemberOrderConflictsRemainServerOwnedFailures() = runBlocking {
        val h = Harness(); var readbacks = 0
        val a = h.actions { readbacks++; true }
        assertEquals(MutationOutcome.Submitted, a.add(h.baseline(), "a"))
        val duplicate = a.lastAction.value!!
        h.activities.value = listOf(
            OperationActivity(
                duplicate.intentId, "add duplicate", MutationStatus.FAILED,
                message = "409 DUPLICATE_SONG", idempotencyKey = "key",
                origin = ServerOrigin.parse("http://music.example:18000"),
            ),
        )
        assertFalse(a.awaitAndRefresh(duplicate))
        assertEquals("409 DUPLICATE_SONG", h.activities.value.single().message)
        assertEquals(0, readbacks)

        assertEquals(MutationOutcome.Submitted, a.reorder(h.baseline(), listOf("b", "a")))
        val reorder = a.lastAction.value!!
        h.activities.value = listOf(
            OperationActivity(
                reorder.intentId, "reorder", MutationStatus.FAILED,
                message = "409 PLAYLIST_MEMBER_ORDER_MISMATCH", idempotencyKey = "key",
                origin = ServerOrigin.parse("http://music.example:18000"),
            ),
        )
        assertFalse(a.awaitAndRefresh(reorder))
        assertEquals("409 PLAYLIST_MEMBER_ORDER_MISMATCH", h.activities.value.single().message)
        assertEquals(0, readbacks)
    }

    @Test fun unknownRetryKeepsIntentAndReadbackRequiresCleanSuccess() = runBlocking {
        val h = Harness(); var refreshed: PlaylistMutationTicket? = null
        val a = h.actions { refreshed = it; true }
        assertEquals(MutationOutcome.Submitted, a.add(h.baseline(), "c"))
        val ticket = a.lastAction.value!!
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.UNKNOWN))
        assertFalse(a.awaitAndRefresh(ticket)); assertNull(refreshed)
        // The uncertain first attempt may already have advanced a playlist revision; retry the
        // original idempotent request even when the pre-write resource baseline is now old.
        h.state = h.state.copy(connectionGeneration = h.state.connectionGeneration + 1)
        assertEquals(MutationOutcome.Submitted, a.retryUnknown(ticket))
        assertEquals(listOf(ticket.intentId), h.retried)
        val retry = a.lastAction.value!!
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.SUCCESS, "sync failed"))
        assertFalse(a.awaitAndRefresh(retry)); assertNull(refreshed)
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.SUCCESS))
        assertTrue(a.awaitAndRefresh(retry)); assertEquals(retry, refreshed)
        assertEquals(1, h.submitted.size)
    }
}
