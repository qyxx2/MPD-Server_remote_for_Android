package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.FavoriteMembersDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheKey
import io.github.qyxx2.mpdserver.remote.runtime.ResourceEntryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class FavoriteMutationTicket(
    val intentId: String,
    val songId: String,
    val targetFavorite: Boolean,
    val attempt: Int = 0,
)

class FavoritesActions internal constructor(
    private val gateway: PlaybackMutationGateway,
    val memberState: StateFlow<ResourceEntryState<FavoriteMembersDto>>,
    private val loadMembers: suspend () -> Unit,
    private val refreshResources: suspend () -> Boolean,
) {
    internal constructor(
        gateway: PlaybackMutationGateway,
        refreshResources: suspend () -> Boolean,
    ) : this(
        gateway = gateway,
        memberState = MutableStateFlow<ResourceEntryState<FavoriteMembersDto>>(
            ResourceEntryState.Ready(FavoriteMembersDto(emptyList())),
        ),
        loadMembers = {},
        refreshResources = refreshResources,
    )

    internal constructor(gateway: PlaybackMutationGateway, runtime: AppRuntime) : this(
        gateway = gateway,
        memberState = runtime.resources.observeResource(
            ResourceCacheKey.get<FavoriteMembersDto>(LibraryApiPaths.favoriteMembers()),
        ),
        loadMembers = { runtime.reads.getFavoriteMembers(); Unit },
        refreshResources = favoriteReadback@{
            val members = runtime.reads.refreshFavoriteMembers()
            if (members !is ApiResult.Success) return@favoriteReadback false
            val collection = runtime.reads.refreshFavorites()
            collection is ApiResult.Success &&
                collection.value.sourceType == io.github.qyxx2.mpdserver.remote.protocol.dto.CollectionSourceTypeDto.FAVORITES
        },
    )

    private val _lastAction = MutableStateFlow<FavoriteMutationTicket?>(null)
    val lastAction: StateFlow<FavoriteMutationTicket?> = _lastAction.asStateFlow()
    private val _refreshGeneration = MutableStateFlow(0L)
    val refreshGeneration: StateFlow<Long> = _refreshGeneration.asStateFlow()

    fun activities() = gateway.activities

    fun canRetryUnknown(intentId: String): Boolean = gateway.canRetry(intentId)

    fun retryUnknown(intentId: String): MutationOutcome? {
        val ticket = _lastAction.value?.takeIf { it.intentId == intentId } ?: return null
        return retryUnknown(ticket)
    }

    suspend fun load() {
        loadMembers()
    }

    fun isMembersFresh(): Boolean = memberState.value is ResourceEntryState.Ready

    fun isFavorite(songId: String): Boolean {
        val state = memberState.value
        val members = when (state) {
            is ResourceEntryState.Ready -> state.value
            is ResourceEntryState.Stale -> state.value
            else -> null
        }
        return members?.songIds?.contains(songId) == true
    }

    fun setFavorite(
        songId: String,
        isFavorite: Boolean,
        resourceFresh: Boolean = true,
    ): MutationOutcome? {
        if (songId.isBlank() || !resourceFresh || memberState.value !is ResourceEntryState.Ready) return null
        val path = LibraryApiPaths.favorite(songId)
        val intentId = gateway.newIntentId("favorite")
        val method = if (isFavorite) "DELETE" else "PUT"
        val outcome = gateway.submitWithIntentId(
            intentId = intentId,
            method = method,
            path = path,
            // PUT is body-required by MutationIntent. The Web endpoint ignores this empty object.
            payload = if (isFavorite) null else "{}",
            label = if (isFavorite) "取消收藏" else "收藏歌曲",
            resourceFresh = resourceFresh,
        )
        if (outcome == MutationOutcome.Submitted || outcome == MutationOutcome.DuplicatePending) {
            _lastAction.value = FavoriteMutationTicket(intentId, songId, !isFavorite)
        }
        return outcome
    }

    fun retryUnknown(ticket: FavoriteMutationTicket): MutationOutcome? {
        val outcome = gateway.retry(ticket.intentId)
        if (outcome == MutationOutcome.Submitted || outcome == MutationOutcome.DuplicatePending) {
            _lastAction.value = ticket.copy(attempt = ticket.attempt + 1)
        }
        return outcome
    }

    suspend fun awaitAndRefresh(ticket: FavoriteMutationTicket): Boolean {
        val activity = gateway.activities.first { activities ->
            activities.firstOrNull { it.intentId == ticket.intentId }?.status
                ?.let { it != MutationStatus.PENDING && it != MutationStatus.SYNCING } == true
        }.first { it.intentId == ticket.intentId }
        if (activity.status != MutationStatus.SUCCESS || activity.syncError != null) return false
        return try {
            val refreshed = refreshResources()
            // Even a partial readback must refresh the visible rows from their shared server cache.
            _refreshGeneration.value += 1
            if (refreshed) {
                true
            } else {
                gateway.recordSynchronizationFailure(ticket.intentId, "Favorites resource readback failed")
                false
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _refreshGeneration.value += 1
            gateway.recordSynchronizationFailure(
                ticket.intentId,
                error.message ?: "Favorites resource readback failed",
            )
            false
        }
    }
}
