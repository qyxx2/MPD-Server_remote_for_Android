package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Captured server identity plus the complete membership to which a playlist edit applies. */
data class PlaylistMutationBaseline(
    val epoch: String,
    val generation: Long,
    val libraryRevision: Long,
    val playlistRevision: Long,
    val playlistId: String?,
    val songIds: List<String>,
    val isSystem: Boolean,
) {
    internal fun matches(state: SnapshotStoreState): Boolean {
        val snapshot = state.snapshot ?: return false
        return state.controlsEligible &&
            state.connectionGeneration == generation &&
            snapshot.epoch == epoch &&
            snapshot.libraryRevision == libraryRevision &&
            snapshot.playlistRevision == playlistRevision
    }
}

data class PlaylistMutationTicket(
    val intentId: String,
    val playlistId: String?,
    val deleted: Boolean,
    val baseline: PlaylistMutationBaseline,
    val attempt: Int = 0,
)

class PlaylistActions internal constructor(
    private val gateway: PlaybackMutationGateway,
    private val refreshResources: suspend (PlaylistMutationTicket) -> Boolean,
) {
    internal constructor(gateway: PlaybackMutationGateway, runtime: AppRuntime) : this(
        gateway,
        refreshResources = playlistReadback@{ ticket ->
        val list = runtime.reads.refreshPlaylists()
        if (list !is ApiResult.Success) return@playlistReadback false
        val playlistId = ticket.playlistId
        if (playlistId == null) return@playlistReadback true
        val listed = list.value.items.firstOrNull { it.playlistId == playlistId }
        if (ticket.deleted) return@playlistReadback listed == null
        if (listed == null) return@playlistReadback false
        val detailRead = runtime.reads.refreshPlaylist(playlistId)
        val membersRead = runtime.reads.refreshPlaylistSongs(playlistId)
        if (detailRead !is ApiResult.Success || membersRead !is ApiResult.Success) return@playlistReadback false
        val detail = detailRead.value
        val memberIds = membersRead.value.items.mapNotNull { it.songId }
        detail.playlistId == playlistId &&
            listed.name == detail.name &&
            listed.isSystem == detail.isSystem &&
            listed.songIds == detail.songIds &&
            membersRead.value.items.all { it.songId != null } &&
            detail.songIds == memberIds
        },
    )

    private val _lastAction = MutableStateFlow<PlaylistMutationTicket?>(null)
    val lastAction: StateFlow<PlaylistMutationTicket?> = _lastAction.asStateFlow()
    private val _refreshGeneration = MutableStateFlow(0L)
    val refreshGeneration: StateFlow<Long> = _refreshGeneration.asStateFlow()
    private val _lastReadback = MutableStateFlow<PlaylistMutationTicket?>(null)
    val lastReadback: StateFlow<PlaylistMutationTicket?> = _lastReadback.asStateFlow()

    fun activities() = gateway.activities

    fun canWrite(resourceFresh: Boolean = true): Boolean = gateway.canSubmit(resourceFresh)

    fun canEdit(
        baseline: PlaylistMutationBaseline?,
        resourceFresh: Boolean,
        membersConsistent: Boolean = true,
    ): Boolean = baseline != null && membersConsistent &&
        baseline.matches(gateway.currentState()) && gateway.canSubmit(resourceFresh)

    fun canRetryUnknown(intentId: String): Boolean = gateway.canRetry(intentId)

    fun retryUnknown(intentId: String): MutationOutcome? {
        val ticket = _lastAction.value?.takeIf { it.intentId == intentId } ?: return null
        return retryUnknown(ticket)
    }

    fun captureBaseline(playlist: PlaylistDto): PlaylistMutationBaseline? {
        val state = gateway.currentState()
        val snapshot = state.snapshot ?: return null
        if (!state.controlsEligible || playlist.playlistId.isBlank()) return null
        return PlaylistMutationBaseline(
            epoch = snapshot.epoch,
            generation = state.connectionGeneration,
            libraryRevision = snapshot.libraryRevision,
            playlistRevision = snapshot.playlistRevision,
            playlistId = playlist.playlistId,
            songIds = playlist.songIds.toList(),
            isSystem = playlist.isSystem,
        )
    }

    private fun captureEnvironment(): PlaylistMutationBaseline? {
        val state = gateway.currentState()
        val snapshot = state.snapshot ?: return null
        if (!state.controlsEligible) return null
        return PlaylistMutationBaseline(
            epoch = snapshot.epoch,
            generation = state.connectionGeneration,
            libraryRevision = snapshot.libraryRevision,
            playlistRevision = snapshot.playlistRevision,
            playlistId = null,
            songIds = emptyList(),
            isSystem = false,
        )
    }

    fun create(name: String, resourceFresh: Boolean = true): MutationOutcome? {
        val normalized = name.trim().takeIf(String::isNotEmpty) ?: return null
        val baseline = captureEnvironment() ?: return null
        return submit(
            baseline = baseline,
            method = "POST",
            path = LibraryApiPaths.playlists(),
            payload = buildJsonObject { put("name", normalized) }.toString(),
            label = "创建歌单 $normalized",
            resourceFresh = resourceFresh,
            playlistId = null,
            deleted = false,
        )
    }

    fun rename(
        baseline: PlaylistMutationBaseline,
        name: String,
        resourceFresh: Boolean = true,
        membersConsistent: Boolean = true,
    ): MutationOutcome? {
        val normalized = name.trim().takeIf(String::isNotEmpty) ?: return null
        val id = baseline.playlistId ?: return null
        if (baseline.isSystem) return null
        return submit(
            baseline, "PATCH", LibraryApiPaths.playlist(id),
            buildJsonObject { put("name", normalized) }.toString(),
            "重命名歌单 $normalized", resourceFresh, membersConsistent, id,
        )
    }

    fun delete(
        baseline: PlaylistMutationBaseline,
        resourceFresh: Boolean = true,
        membersConsistent: Boolean = true,
    ): MutationOutcome? {
        val id = baseline.playlistId ?: return null
        if (baseline.isSystem) return null
        return submit(
            baseline, "DELETE", LibraryApiPaths.playlist(id), null,
            "删除歌单", resourceFresh, membersConsistent, id, deleted = true,
        )
    }

    /** Duplicate additions intentionally reach the server so its canonical 409 is preserved. */
    fun add(
        baseline: PlaylistMutationBaseline,
        songId: String,
        resourceFresh: Boolean = true,
        membersConsistent: Boolean = true,
    ): MutationOutcome? {
        val id = baseline.playlistId ?: return null
        if (songId.isBlank()) return null
        return submit(
            baseline, "POST", LibraryApiPaths.playlistSongs(id),
            buildJsonObject { put("song_id", songId) }.toString(),
            "添加歌曲到歌单", resourceFresh, membersConsistent, id,
        )
    }

    fun remove(
        baseline: PlaylistMutationBaseline,
        songId: String,
        resourceFresh: Boolean = true,
        membersConsistent: Boolean = true,
    ): MutationOutcome? {
        val id = baseline.playlistId ?: return null
        if (songId.isBlank() || songId !in baseline.songIds) return null
        return submit(
            baseline, "DELETE", LibraryApiPaths.playlistSong(id, songId), null,
            "从歌单移除歌曲", resourceFresh, membersConsistent, id,
        )
    }

    /** The caller must pass the full membership, not the currently visible LazyColumn window. */
    fun reorder(
        baseline: PlaylistMutationBaseline,
        orderedSongIds: List<String>,
        resourceFresh: Boolean = true,
        membersConsistent: Boolean = true,
    ): MutationOutcome? {
        val id = baseline.playlistId ?: return null
        if (orderedSongIds.size != baseline.songIds.size ||
            orderedSongIds.sorted() != baseline.songIds.sorted()
        ) return null
        val payload = buildJsonObject {
            put("ordered_song_ids", buildJsonArray {
                orderedSongIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        }.toString()
        return submit(
            baseline, "PUT", LibraryApiPaths.playlistSongsOrder(id), payload,
            "调整歌单顺序", resourceFresh, membersConsistent, id,
        )
    }

    private fun submit(
        baseline: PlaylistMutationBaseline,
        method: String,
        path: String,
        payload: String?,
        label: String,
        resourceFresh: Boolean,
        membersConsistent: Boolean = true,
        playlistId: String? = baseline.playlistId,
        deleted: Boolean = false,
    ): MutationOutcome? {
        if (!resourceFresh || !membersConsistent || !baseline.matches(gateway.currentState())) return null
        val id = gateway.newIntentId("playlist")
        val outcome = gateway.submitWithIntentId(
            intentId = id,
            method = method,
            path = path,
            payload = payload,
            label = label,
            resourceFresh = resourceFresh,
            precondition = { membersConsistent && baseline.matches(gateway.currentState()) },
        )
        if (outcome == MutationOutcome.Submitted || outcome == MutationOutcome.DuplicatePending) {
            _lastAction.value = PlaylistMutationTicket(id, playlistId, deleted, baseline)
        }
        return outcome
    }

    fun retryUnknown(ticket: PlaylistMutationTicket): MutationOutcome? {
        // Unknown retries replay the exact stored request/idempotency key. Do not re-evaluate
        // the pre-write revision baseline: the first attempt may already have advanced it.
        return gateway.retry(ticket.intentId).also { outcome ->
            if (outcome == MutationOutcome.Submitted || outcome == MutationOutcome.DuplicatePending) {
                _lastAction.value = ticket.copy(attempt = ticket.attempt + 1)
            }
        }
    }

    suspend fun awaitAndRefresh(ticket: PlaylistMutationTicket): Boolean {
        val activity = gateway.activities.first { activities ->
            activities.firstOrNull { it.intentId == ticket.intentId }?.status
                ?.let { it != MutationStatus.PENDING && it != MutationStatus.SYNCING } == true
        }.first { it.intentId == ticket.intentId }
        if (activity.status != MutationStatus.SUCCESS || activity.syncError != null) return false
        return try {
            val isConsistent = refreshResources(ticket)
            // Publish a UI reload even after a partial/mismatched readback so the screen renders
            // the newly fetched server resources and can visibly disable inconsistent editing.
            _refreshGeneration.value += 1
            if (isConsistent) {
                _lastReadback.value = ticket
                true
            } else {
                gateway.recordSynchronizationFailure(ticket.intentId, "Playlist resource readback failed")
                false
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _refreshGeneration.value += 1
            gateway.recordSynchronizationFailure(
                ticket.intentId,
                error.message ?: "Playlist resource readback failed",
            )
            false
        }
    }
}
