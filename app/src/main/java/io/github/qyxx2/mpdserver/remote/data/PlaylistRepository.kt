package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistListDto

class PlaylistRepository(transport: HttpTransport) : ApiRepository(transport) {
    fun getPlaylists(): ApiResult<PlaylistListDto> = get("/api/playlists")
}
