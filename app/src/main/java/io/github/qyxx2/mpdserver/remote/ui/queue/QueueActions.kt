package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.QueueMutationScope
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

internal enum class QueueMove { NEXT, ADD }

/**
 * Web-equivalent Queue mutations. This class owns no transport or queue state: every intent is
 * submitted to the process-wide Runtime MutationCoordinator and the canonical snapshot remains
 * the only source of truth after receipt/readback.
 */
internal class QueueActions(
    private val stateProvider: () -> SnapshotStoreState,
    private val activitiesProvider: () -> List<OperationActivity>,
    private val submitter: (MutationIntent) -> MutationOutcome,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    var lastSubmittedIntentId: String? = null
        private set
    val isEnabled: Boolean get() = baseline() != null && !hasUnresolvedMutation()

    fun canShowMenu(queueItemId: String): Boolean {
        // Web exposes a menu on each visible row; its operation handlers validate occurrence
        // membership and allowed position individually. Keep the same affordance contract.
        return isEnabled && queueItemId.isNotBlank()
    }

    fun play(queueItemId: String, title: String): MutationOutcome? {
        val items = queueItems() ?: return null
        if (!isEnabled || items.none { it.queueItemId == queueItemId }) return null
        return submit(
            method = "POST",
            path = "/api/playback/queue/items/${encodePathSegment(queueItemId)}/play",
            payload = "{}",
            label = "队列 · 播放 $title · 条目 $queueItemId",
        )
    }

    fun move(queueItemId: String, operation: QueueMove, title: String): MutationOutcome? {
        val items = queueItems()?.filter { it.position > 0 }?.sortedBy { it.position } ?: return null
        if (!isEnabled) return null
        val index = items.indexOfFirst { it.queueItemId == queueItemId }
        if (index < 0) return null
        val before = when (operation) {
            QueueMove.NEXT -> items.firstOrNull()?.queueItemId
            QueueMove.ADD -> null
        }
        if (operation == QueueMove.NEXT && before == queueItemId) return null
        if (operation == QueueMove.ADD && items.lastOrNull()?.queueItemId == queueItemId) return null
        val payload = buildJsonObject {
            if (before == null) put("before_queue_item_id", JsonNull)
            else put("before_queue_item_id", before)
        }.toString()
        val verb = if (operation == QueueMove.NEXT) "移为下一首" else "移到队尾"
        return submit(
            method = "PUT",
            path = "/api/playback/queue/items/${encodePathSegment(queueItemId)}",
            payload = payload,
            label = "队列 · $verb $title · 条目 $queueItemId",
        )
    }

    fun remove(queueItemId: String, title: String): MutationOutcome? {
        if (!isEnabled) return null
        val items = queueItems() ?: return null
        // Match the Web handler: the row action is available for any visible occurrence present
        // in the canonical snapshot; the service remains authoritative for mutation validity.
        if (items.none { it.queueItemId == queueItemId }) return null
        return submit(
            method = "DELETE",
            path = "/api/playback/queue/items/${encodePathSegment(queueItemId)}",
            payload = null,
            label = "队列 · 删除 $title · 条目 $queueItemId",
        )
    }

    fun clear(): MutationOutcome? {
        if (!isEnabled) return null
        return submit("DELETE", "/api/playback/queue", null, "队列 · 清空待播")
    }

    fun save(name: String): MutationOutcome? {
        // Match Web: reject blank-only input, but submit the original field value unchanged.
        if (!isEnabled || name.isBlank()) return null
        val payload = buildJsonObject { put("name", name) }.toString()
        return submit(
            "POST",
            "/api/playback/queue/save-as-playlist",
            payload,
            "队列 · 保存为 Playlist · ${name.trim()}",
        )
    }

    private fun encodePathSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code || code == '-'.code || code == '.'.code ||
                code == '_'.code || code == '~'.code || code == '!'.code || code == '*'.code ||
                code == '\''.code || code == '('.code || code == ')'.code
            ) append(code.toChar())
            else append('%').append("0123456789ABCDEF"[code ushr 4]).append("0123456789ABCDEF"[code and 0x0f])
        }
    }

    private fun submit(method: String, path: String, payload: String?, label: String): MutationOutcome {
        val id = "queue:${idFactory()}"
        val retryScope = QueueMutationScope.fingerprint(stateProvider()) ?: return MutationOutcome.NotRetryable
        val intent = MutationIntent(
            id = id,
            method = method,
            path = path,
            payload = payload,
            label = label,
            retryScope = retryScope,
        )
        lastSubmittedIntentId = id
        return submitter(intent)
    }

    private fun baseline(): Pair<SnapshotStoreState, QueueSnapshotDto>? {
        val state = stateProvider()
        if (!state.controlsEligible || state.connectionStatus != RuntimeConnectionStatus.CONNECTED ||
            state.snapshotConnectionGeneration != state.connectionGeneration
        ) return null
        val snapshot = state.snapshot ?: return null
        val queue = runCatching {
            JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(snapshot.wire.queue)
        }.getOrNull() ?: return null
        return state to queue
    }

    private fun queueItems(): List<QueueItemDto>? = baseline()?.second?.items

    private fun hasUnresolvedMutation(): Boolean = activitiesProvider().any {
        it.status in setOf(MutationStatus.PENDING, MutationStatus.SYNCING, MutationStatus.UNKNOWN)
    }
}
