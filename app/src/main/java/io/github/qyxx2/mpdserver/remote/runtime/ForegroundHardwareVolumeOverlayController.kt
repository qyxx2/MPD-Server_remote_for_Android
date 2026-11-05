package io.github.qyxx2.mpdserver.remote.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Short-lived display projection. It is never fed back into SnapshotStore or persisted. */
data class HardwareVolumeOverlayContent(
    val valueText: String,
    val pending: Boolean = false,
    val statusText: String? = null,
)

data class HardwareVolumeOverlayState(
    val visible: Boolean = false,
    val content: HardwareVolumeOverlayContent = HardwareVolumeOverlayContent(valueText = "—"),
    /** Recreate the app Popup after another app-owned Popup takes the top layer. */
    val windowOrderSerial: Long = 0L,
)

/** The visibility deadline is driven only by physical key events, never by asynchronous readback. */
class ForegroundHardwareVolumeOverlayController(
    private val scope: CoroutineScope,
    private val presentationProvider: () -> HardwareVolumeOverlayContent,
    private val onVolumeKey: (direction: Int) -> Unit,
    private val hideAfterMillis: Long = HIDE_AFTER_MILLIS,
) : AutoCloseable {
    private val _state = MutableStateFlow(
        HardwareVolumeOverlayState(content = presentationProvider()),
    )
    val state: StateFlow<HardwareVolumeOverlayState> = _state.asStateFlow()
    private var hideJob: Job? = null

    fun onVolumeKey(direction: Int) {
        if (direction != -1 && direction != 1) return
        onVolumeKey.invoke(direction)
        _state.value = _state.value.copy(
            visible = true,
            content = presentationProvider(),
        )
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(hideAfterMillis.coerceAtLeast(0L))
            _state.value = _state.value.copy(visible = false)
        }
    }

    /** Updates content while preserving visibility and its original event-based deadline. */
    fun updatePresentation(content: HardwareVolumeOverlayContent) {
        _state.value = _state.value.copy(content = content)
    }

    fun refreshPresentation() = updatePresentation(presentationProvider())

    /** Re-add the non-focusable app Popup above a newly opened app-owned Popup/Dialog. */
    fun bringToFront() {
        if (_state.value.visible) {
            _state.value = _state.value.copy(windowOrderSerial = _state.value.windowOrderSerial + 1L)
        }
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        _state.value = _state.value.copy(visible = false)
    }

    override fun close() = hide()

    companion object {
        const val HIDE_AFTER_MILLIS = 2_000L
    }
}

/** Project the only canonical snapshot plus the gateway's transient, unconfirmed input preview. */
object HardwareVolumeOverlayPresentation {
    fun from(
        snapshot: SnapshotStoreState,
        command: HardwareVolumeCommandState,
    ): HardwareVolumeOverlayContent {
        val capability = HardwareVolumeCapabilityPolicy.evaluate(snapshot)
        val commandStatus = when (command.phase.takeIf { capability.canWrite }) {
            HardwareVolumeCommandPhase.UNKNOWN -> "结果未知"
            HardwareVolumeCommandPhase.FAILED -> "调整失败"
            HardwareVolumeCommandPhase.QUEUED, HardwareVolumeCommandPhase.IN_FLIGHT -> "调整中"
            HardwareVolumeCommandPhase.IDLE, null -> null
        }
        val unavailableStatus = when {
            snapshot.connectionStatus != RuntimeConnectionStatus.CONNECTED -> "未连接"
            snapshot.freshness != SnapshotFreshness.FRESH -> "已过期"
            capability.status == HardwareVolumeCapabilityStatus.UNSUPPORTED -> "不支持"
            capability.status == HardwareVolumeCapabilityStatus.UNAVAILABLE -> "不可用"
            !capability.canWrite -> "未知"
            else -> null
        }
        val pending = capability.canWrite &&
            command.phase in setOf(HardwareVolumeCommandPhase.QUEUED, HardwareVolumeCommandPhase.IN_FLIGHT) &&
            command.previewVolumePercent != null
        val value = if (pending) command.previewVolumePercent else capability.displayVolumePercent
        return HardwareVolumeOverlayContent(
            valueText = value?.takeIf { it in 0..100 }?.toString() ?: "—",
            pending = pending,
            statusText = commandStatus ?: unavailableStatus,
        )
    }
}
