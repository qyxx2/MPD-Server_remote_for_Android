package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import kotlinx.serialization.json.JsonElement

class PlaybackRepository(transport: HttpTransport) : ApiRepository(transport) {
    fun getPlaybackState(): ApiResult<PlaybackStateDto> = get("/api/playback/state")
    fun getQueue(): ApiResult<JsonElement> = get("/api/playback/queue")
}
