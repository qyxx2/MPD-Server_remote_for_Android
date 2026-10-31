package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheIdentityState

sealed interface LibraryLoadState<out T> {
    data object Loading : LibraryLoadState<Nothing>
    data object Empty : LibraryLoadState<Nothing>
    data class Ready<T>(val value: T) : LibraryLoadState<T>
    data class Stale<T>(val value: T) : LibraryLoadState<T>
    data class Failed(val message: String) : LibraryLoadState<Nothing>
}

internal data class LibraryReadIdentity(
    val session: io.github.qyxx2.mpdserver.remote.runtime.ResourceSessionIdentity?,
    val libraryRevision: Long?,
    val playlistRevision: Long?,
    val dependsOnPlaylistRevision: Boolean,
    val isClosed: Boolean,
) {
    fun matches(state: ResourceCacheIdentityState): Boolean =
        !state.isClosed && state.session == session &&
            state.watermarks?.libraryRevision == libraryRevision &&
            (!dependsOnPlaylistRevision || state.watermarks?.playlistRevision == playlistRevision)
}

/**
 * Screen-local presentation state over the single AppRuntime resource owner.
 * A route change cancels only the caller's await; cached GETs/in-flight de-duplication remain runtime-owned.
 */
@Composable
internal fun <T : Any> rememberLibraryLoadState(
    runtime: AppRuntime,
    resourceKey: String,
    retryToken: Int = 0,
    dependsOnPlaylistRevision: Boolean = false,
    load: suspend () -> ApiResult<T>,
): LibraryLoadState<T> {
    val identityState by runtime.resources.identityState.collectAsStateWithLifecycle()
    val identity = LibraryReadIdentity(
        session = identityState.session,
        libraryRevision = identityState.watermarks?.libraryRevision,
        playlistRevision = identityState.watermarks?.playlistRevision.takeIf { dependsOnPlaylistRevision },
        dependsOnPlaylistRevision = dependsOnPlaylistRevision,
        isClosed = identityState.isClosed,
    )
    var state by remember(resourceKey, identity) { mutableStateOf<LibraryLoadState<T>>(LibraryLoadState.Loading) }

    LaunchedEffect(resourceKey, identity, identityState.isFresh, retryToken) {
        if (identity.isClosed) {
            state = LibraryLoadState.Failed("资源缓存已关闭")
            return@LaunchedEffect
        }
        if (identity.session == null || identity.libraryRevision == null) return@LaunchedEffect
        state = when (val result = try {
            load()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ApiResult.ProtocolError(error.message ?: "音乐库读取失败")
        }) {
            is ApiResult.Success -> {
                val latest = runtime.resources.identityState.value
                if (!identity.matches(latest)) return@LaunchedEffect
                if (latest.isFresh) LibraryLoadState.Ready(result.value)
                else LibraryLoadState.Stale(result.value)
            }
            ApiResult.NoContent -> {
                val latest = runtime.resources.identityState.value
                if (!identity.matches(latest)) return@LaunchedEffect
                LibraryLoadState.Empty
            }
            else -> {
                val latest = runtime.resources.identityState.value
                if (!identity.matches(latest)) return@LaunchedEffect
                LibraryLoadState.Failed(result.libraryMessage())
            }
        }
    }

    return when {
        !identityState.isFresh && state is LibraryLoadState.Ready ->
            LibraryLoadState.Stale((state as LibraryLoadState.Ready<T>).value)
        else -> state
    }
}

internal fun ApiResult<*>.libraryMessage(): String = when (this) {
    is ApiResult.BusinessError -> error.message
    is ApiResult.HttpError -> message
    is ApiResult.TransportError -> "无法连接音乐服务器"
    is ApiResult.DecodeError -> message
    is ApiResult.ProtocolError -> if (message.contains("snapshot is stale", true)) {
        "连接暂不可用，当前没有可显示的缓存数据"
    } else message
    ApiResult.NoContent -> "服务器未返回内容"
    is ApiResult.Accepted -> "读取请求意外进入异步队列"
    is ApiResult.Success<*> -> "未知读取状态"
}

/** Re-typing an API failure is safe because every failure subtype carries no success payload. */
internal fun <T> ApiResult<*>.asFailure(): ApiResult<T> = when (this) {
    is ApiResult.Success<*> -> ApiResult.ProtocolError("A successful response was used where a failure was expected")
    is ApiResult.Accepted -> ApiResult.Accepted(activityId, statusCode)
    ApiResult.NoContent -> ApiResult.NoContent
    is ApiResult.BusinessError -> ApiResult.BusinessError(statusCode, error)
    is ApiResult.HttpError -> ApiResult.HttpError(statusCode, message, contentType)
    is ApiResult.TransportError -> ApiResult.TransportError(failure)
    is ApiResult.DecodeError -> ApiResult.DecodeError(statusCode, message)
    is ApiResult.ProtocolError -> ApiResult.ProtocolError(message)
}


private fun <T> LibraryLoadState<T>.asStale(): LibraryLoadState<T> = when (this) {
    is LibraryLoadState.Ready -> LibraryLoadState.Stale(value)
    is LibraryLoadState.Stale -> this
    LibraryLoadState.Loading -> LibraryLoadState.Loading
    LibraryLoadState.Empty -> LibraryLoadState.Empty
    is LibraryLoadState.Failed -> this
}
