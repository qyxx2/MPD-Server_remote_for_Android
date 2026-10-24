package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.decodeJson

abstract class ApiRepository(protected val transport: HttpTransport) {
    protected inline fun <reified T> get(path: String): ApiResult<T> = transport.execute(transport.buildRequest("GET", path)).decodeJson()
    protected inline fun <reified T> send(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): ApiResult<T> =
        transport.execute(transport.buildRequest(method, path, body, headers)).decodeJson()
}
