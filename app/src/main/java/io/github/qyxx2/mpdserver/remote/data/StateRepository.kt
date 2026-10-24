package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto

class StateRepository(transport: HttpTransport) : ApiRepository(transport) {
    fun getState(): ApiResult<StateSnapshotDto> = get("/api/state")
}
