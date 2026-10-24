package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SystemOutputDto
import kotlinx.serialization.json.JsonElement

class SystemRepository(transport: HttpTransport) : ApiRepository(transport) {
    fun getOutput(): ApiResult<SystemOutputDto> = get("/api/system/output")
    fun getMpd(): ApiResult<JsonElement> = get("/api/system/mpd")
}
