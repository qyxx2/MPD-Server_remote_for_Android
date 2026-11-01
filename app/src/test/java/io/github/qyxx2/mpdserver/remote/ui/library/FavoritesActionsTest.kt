package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.*
import io.github.qyxx2.mpdserver.remote.protocol.dto.FavoriteMembersDto
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FavoritesActionsTest {
    private class Harness {
        val activities = MutableStateFlow<List<OperationActivity>>(emptyList())
        val submitted = mutableListOf<MutationIntent>()
        val retried = mutableListOf<String>()
        var nextId = 0
        val gateway = PlaybackMutationGateway(
            stateProvider = { PlayerTestFixtures.state() },
            activities = activities,
            submitter = { submitted += it; MutationOutcome.Submitted },
            retryUnknown = { retried += it; MutationOutcome.Submitted },
            canRetryUnknown = { id -> activities.value.any { it.intentId == id && it.status == MutationStatus.UNKNOWN } },
            idFactory = { "favorite-" + (++nextId) },
        )
        fun actions(refresh: suspend () -> Boolean = { true }) = FavoritesActions(gateway, refreshResources = refresh)
        fun activity(id: String, status: MutationStatus, error: String? = null) = OperationActivity(
            id, "favorite", status, syncError = error, idempotencyKey = "key",
            origin = ServerOrigin.parse("http://music.example:18000"),
        )
    }

    @Test fun membershipProjectionUsesOneSharedStateAndRetainsStaleLastKnownIds() {
        val h = Harness()
        val members = MutableStateFlow<ResourceEntryState<FavoriteMembersDto>>(
            ResourceEntryState.Ready(FavoriteMembersDto(listOf("song-1", "orphan-id"))),
        )
        val actions = FavoritesActions(
            gateway = h.gateway,
            memberState = members,
            loadMembers = {},
            refreshResources = { true },
        )
        assertTrue(actions.isFavorite("song-1"))
        assertTrue(actions.isFavorite("orphan-id"))
        assertFalse(actions.isFavorite("song-2"))
        members.value = ResourceEntryState.Stale(FavoriteMembersDto(listOf("orphan-id")))
        assertTrue(actions.isFavorite("orphan-id"))
        assertFalse(actions.isMembersFresh())
        assertNull(actions.setFavorite("orphan-id", isFavorite = true))
    }

    @Test fun putAndDeleteUseExactPathAndBodyContract() {
        val h = Harness(); val a = h.actions()
        assertEquals(MutationOutcome.Submitted, a.setFavorite("song #1", isFavorite = false))
        assertEquals(MutationOutcome.Submitted, a.setFavorite("song #1", isFavorite = true))
        assertEquals(listOf("PUT", "DELETE"), h.submitted.map { it.method })
        assertEquals(listOf("/api/favorites/song%20%231", "/api/favorites/song%20%231"), h.submitted.map { it.path })
        assertEquals("{}", h.submitted[0].payload); assertNull(h.submitted[1].payload)
    }

    @Test fun orphanMemberCanBeUnfavoritedWithoutSongMetadata() {
        val h = Harness()
        assertEquals(MutationOutcome.Submitted, h.actions().setFavorite("orphan-id", isFavorite = true))
        assertEquals("/api/favorites/orphan-id", h.submitted.single().path)
    }

    @Test fun staleResourceAndGlobalUnresolvedMutationBlockFavoriteWrites() {
        val h = Harness(); val a = h.actions()
        assertNull(a.setFavorite(" ", false))
        assertNull(a.setFavorite("song", false, resourceFresh = false))
        h.activities.value = listOf(h.activity("queue:unknown", MutationStatus.UNKNOWN))
        assertNull(a.setFavorite("song", false))
        assertTrue(h.submitted.isEmpty())
    }

    @Test fun unknownRetryUsesSameIntentIdAndNoSecondSubmission() {
        val h = Harness(); val a = h.actions()
        assertEquals(MutationOutcome.Submitted, a.setFavorite("song", false))
        val ticket = a.lastAction.value!!
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.UNKNOWN))
        assertEquals(MutationOutcome.Submitted, a.retryUnknown(ticket))
        assertEquals(listOf(ticket.intentId), h.retried)
        assertEquals(1, h.submitted.size)
    }

    @Test fun resourceReadbackRequiresMatchingCleanSuccessAndFailureDoesNotClearIdentity() = runBlocking {
        val h = Harness(); var count = 0
        val a = h.actions { count++; true }
        a.setFavorite("song", false); val ticket = a.lastAction.value!!
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.UNKNOWN))
        assertFalse(a.awaitAndRefresh(ticket)); assertEquals(0, count)
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.SUCCESS, "snapshot readback failed"))
        assertFalse(a.awaitAndRefresh(ticket)); assertEquals(0, count)
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.SUCCESS))
        assertTrue(a.awaitAndRefresh(ticket)); assertEquals(1, count)
    }

    @Test fun failedResourceReadbackIsReportedAsFailure() = runBlocking {
        val h = Harness(); val a = h.actions { false }
        a.setFavorite("song", false); val ticket = a.lastAction.value!!
        h.activities.value = listOf(h.activity(ticket.intentId, MutationStatus.SUCCESS))
        assertFalse(a.awaitAndRefresh(ticket))
        assertEquals(ticket.intentId, a.lastAction.value?.intentId)
        assertEquals(1L, a.refreshGeneration.value)
    }
}
