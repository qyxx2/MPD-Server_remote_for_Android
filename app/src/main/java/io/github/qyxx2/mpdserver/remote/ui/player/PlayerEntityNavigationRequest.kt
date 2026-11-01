package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtistListDto
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryRoutes

enum class PlayerEntityType { ARTIST, ALBUM }

/** Resolved route request handed to the app-level navigation boundary; it is not playback state. */
data class PlayerEntityNavigationRequest(
    val type: PlayerEntityType,
    val id: String,
    val displayName: String,
) {
    init {
        require(id.isNotBlank()) { "Entity id must be resolved before navigation" }
        require(displayName.isNotBlank()) { "Entity display name must not be blank" }
    }
}

/** Mirrors Web PlayerPanel's exact-name lookup against the existing artist list endpoint. */
internal fun resolveArtistNavigationRequest(
    displayName: String,
    artists: ArtistListDto,
): PlayerEntityNavigationRequest? {
    if (displayName.isBlank()) return null
    val match = artists.items.firstOrNull { it.name == displayName } ?: return null
    return PlayerEntityNavigationRequest(
        type = PlayerEntityType.ARTIST,
        id = match.artistId,
        displayName = match.name,
    )
}

/** Album requests use the canonical Library detail route and the opaque server-owned album ID. */
fun PlayerEntityNavigationRequest.albumRouteOrNull(): String? =
    if (type == PlayerEntityType.ALBUM) LibraryRoutes.album(id) else null
