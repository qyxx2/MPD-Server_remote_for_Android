package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongActionsTest {
    @Test
    fun actionsUseExactWebSongRoutesAndExplicitEmptyJsonBody() {
        val h = Harness()
        val actions = h.actions()
        val item = row("song/with space")

        assertEquals(MutationOutcome.Submitted, actions.perform(item, SongPlaybackAction.PLAY_NOW))
        assertEquals(MutationOutcome.Submitted, actions.perform(item, SongPlaybackAction.PLAY_NEXT))
        assertEquals(MutationOutcome.Submitted, actions.perform(item, SongPlaybackAction.ADD_TO_QUEUE))
        assertEquals(h.submitted.last().id, actions.lastSubmittedIntentId)

        assertEquals(
            listOf(
                "/api/playback/songs/song%2Fwith%20space/play",
                "/api/playback/songs/song%2Fwith%20space/play-next",
                "/api/playback/songs/song%2Fwith%20space/queue",
            ),
            h.submitted.map { it.path },
        )
        assertEquals(listOf("POST", "POST", "POST"), h.submitted.map { it.method })
        assertEquals(listOf("{}", "{}", "{}"), h.submitted.map { it.payload })
        assertEquals(3, h.submitted.map { it.id }.distinct().size)
    }

    @Test
    fun rejectsMissingSongIdUnavailableSongAndMismatchedProjection() {
        val h = Harness()
        val actions = h.actions()
        val missingId = row(null)
        val unavailable = row("missing").copy(
            song = row("missing").song!!.copy(availabilityStatus = AvailabilityStatus.MISSING),
        )
        val mismatched = row("other").copy(songId = "different")

        assertNull(actions.perform(missingId, SongPlaybackAction.PLAY_NOW))
        assertNull(actions.perform(unavailable, SongPlaybackAction.PLAY_NEXT))
        assertNull(actions.perform(mismatched, SongPlaybackAction.ADD_TO_QUEUE))
        assertTrue(h.submitted.isEmpty())
    }

    @Test
    fun rejectsUnindexedUnreadableAndStaleResourceRows() {
        val h = Harness()
        val actions = h.actions()
        val unreadable = row("unreadable").copy(
            song = row("unreadable").song!!.copy(availabilityStatus = AvailabilityStatus.UNREADABLE),
        )
        val unindexed = row("unindexed").copy(song = null)

        assertNull(actions.perform(unreadable, SongPlaybackAction.PLAY_NOW))
        assertNull(actions.perform(unindexed, SongPlaybackAction.PLAY_NOW))
        assertNull(actions.perform(row("stale"), SongPlaybackAction.PLAY_NOW, resourceFresh = false))
        assertTrue(h.submitted.isEmpty())
    }

    @Test
    fun staleSnapshotDisconnectedRuntimeAndUnresolvedGlobalMutationBlockWrites() {
        val stale = Harness(state = { PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE) })
        assertNull(stale.actions().perform(row("song-1"), SongPlaybackAction.PLAY_NOW))

        val disconnected = Harness(state = {
            PlayerTestFixtures.state(connectionStatus = io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus.DISCONNECTED)
        })
        assertNull(disconnected.actions().perform(row("song-1"), SongPlaybackAction.PLAY_NOW))

        val locked = Harness(
            initialActivities = listOf(activity("queue:unknown", MutationStatus.UNKNOWN)),
        )
        assertFalse(locked.actions().canPerform(row("song-1")))
        assertNull(locked.actions().perform(row("song-1"), SongPlaybackAction.PLAY_NOW))
        assertTrue(locked.submitted.isEmpty())
    }

    @Test
    fun confirmedReceiptWithReadbackErrorRemainsGloballyWriteBlocking() {
        val h = Harness(
            initialActivities = listOf(
                OperationActivity(
                    intentId = "collection:failed-readback",
                    label = "Collection playback",
                    status = MutationStatus.SUCCESS,
                    message = "Write confirmed; state readback failed",
                    syncError = "snapshot GET unavailable",
                    idempotencyKey = "key",
                    origin = ServerOrigin.parse("http://music.example:18000"),
                ),
            ),
        )
        assertFalse(h.actions().canPerform(row("song-1")))
        assertNull(h.actions().perform(row("song-1"), SongPlaybackAction.PLAY_NOW))
        assertTrue(h.submitted.isEmpty())
    }

    @Test
    fun matchingActivityIsExposedAndOnlySuccessWithoutSyncErrorIsReconciled() {
        val h = Harness()
        val actions = h.actions()
        actions.perform(row("song-1"), SongPlaybackAction.PLAY_NOW)
        val id = h.submitted.single().id
        val submittedActivity = activity(id, MutationStatus.PENDING)
        h.activities.value = listOf(submittedActivity)
        assertEquals(submittedActivity, actions.activity(id))
        assertFalse(actions.isReconciled(id))

        h.activities.value = listOf(submittedActivity.copy(status = MutationStatus.SUCCESS, syncError = "readback"))
        assertFalse(actions.isReconciled(id))
        h.activities.value = listOf(submittedActivity.copy(status = MutationStatus.SUCCESS, syncError = null))
        assertTrue(actions.isReconciled(id))
    }

    @Test
    fun explicitUnknownRetryUsesExistingIntentIdAndDoesNotCreateNewIntent() {
        val h = Harness()
        val actions = h.actions()
        actions.perform(row("song-1"), SongPlaybackAction.PLAY_NOW)
        val original = h.submitted.single()
        h.activities.value = listOf(activity(original.id, MutationStatus.UNKNOWN))

        assertEquals(MutationOutcome.Submitted, actions.retryUnknown(original.id))
        assertEquals(listOf(original), h.submitted)
        assertEquals(listOf(original.id), h.retried)
        assertNull(actions.retryUnknown("not-found"))
    }

    private class Harness(
        private val state: () -> io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState = {
            PlayerTestFixtures.state()
        },
        initialActivities: List<OperationActivity> = emptyList(),
    ) {
        val activities = MutableStateFlow(initialActivities)
        val submitted = mutableListOf<MutationIntent>()
        val retried = mutableListOf<String>()
        private var nextId = 0

        fun actions() = SongActions(
            gateway = PlaybackMutationGateway(
                stateProvider = state,
                activities = activities,
                submitter = { intent -> submitted += intent; MutationOutcome.Submitted },
                retryUnknown = { intentId -> retried += intentId; MutationOutcome.Submitted },
                canRetryUnknown = { intentId -> activities.value.any { it.intentId == intentId && it.status == MutationStatus.UNKNOWN } },
                idFactory = { "intent-${++nextId}" },
            ),
        )
    }

    private fun activity(id: String, status: MutationStatus) = OperationActivity(
        intentId = id,
        label = "test",
        status = status,
        idempotencyKey = "key",
        origin = ServerOrigin.parse("http://music.example:18000"),
    )

    private fun row(id: String?): LibrarySongRowModel {
        val song = id?.let {
            SongDto(
                songId = it, title = "Song $it", fileUri = "music/$it.flac", artists = emptyList(),
                albumId = null, album = null, albumArtists = emptyList(), trackNumber = null,
                discNumber = null, year = null, date = null, genres = emptyList(), tagNames = emptyList(),
                duration = null, lyrics = null, lyricsFormat = null, lyricsSource = null,
                bitDepth = null, sampleRateHz = null, channelCount = null, codec = null,
                metadataStatus = null, lastScannedAt = null, fileSize = null, fileMtimeNs = null,
                contentHash = null, availabilityStatus = AvailabilityStatus.AVAILABLE, lastSeenAt = null,
                artwork = null,
            )
        }
        return LibrarySongRowModel(key = "song:${id ?: "no-id"}", songId = id, song = song)
    }
}
