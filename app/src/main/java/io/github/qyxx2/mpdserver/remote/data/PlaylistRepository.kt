package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto

class PlaylistRepository(transport: HttpTransport) : ApiRepository(transport) {
    fun getPlaylists(): ApiResult<List<PlaylistDto>> = get("/api/playlists")
}
