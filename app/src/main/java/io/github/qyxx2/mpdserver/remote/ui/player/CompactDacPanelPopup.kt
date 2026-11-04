package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapability
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapabilityStatus
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCommandPhase
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCommandState
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionAnchorState
import io.github.qyxx2.mpdserver.remote.ui.components.AnchoredDropdownPositionProvider
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionHost
import java.util.Locale
import kotlin.math.roundToInt

/** Compact DAC/output summary and server-authoritative hardware-volume control. */
@Composable
internal fun CompactDacPanelPopup(
    output: OutputPresentation,
    hardwareVolume: HardwareVolumeCapability,
    visible: Boolean,
    onDismiss: () -> Unit,
    anchorState: PopupMotionAnchorState,
    commandState: HardwareVolumeCommandState = HardwareVolumeCommandState(),
    onVolumeCommit: (Int) -> Unit = {},
) {
    val volume = hardwareVolume.displayVolumePercent?.takeIf { it in 0..100 }
    var sliderDraft by remember { mutableStateOf(HardwareVolumeSliderDraft()) }
    val currentCanonicalTuple = rememberUpdatedState(hardwareVolume.canonicalTuple)
    val currentCanWrite = rememberUpdatedState(hardwareVolume.canWrite)
    val currentCommandPhase = rememberUpdatedState(commandState.phase)
    LaunchedEffect(hardwareVolume.canonicalTuple) {
        sliderDraft = sliderDraft.invalidateIfCanonicalChanged(hardwareVolume.canonicalTuple)
    }
    val previewVolume = sliderDraft.previewPercent?.roundToInt()
    val sliderEnabled = hardwareVolume.canWrite && volume != null && commandState.phase != HardwareVolumeCommandPhase.UNKNOWN
    val commandPending = commandState.phase == HardwareVolumeCommandPhase.QUEUED ||
        commandState.phase == HardwareVolumeCommandPhase.IN_FLIGHT
    val outputInfo = buildDacOutputLine(output)
    val outputStatus = output.dacStatus.toChineseLabel()
    val volumeStatus = when {
        hardwareVolume.status == HardwareVolumeCapabilityStatus.UNSUPPORTED -> "服务端不支持"
        hardwareVolume.status == HardwareVolumeCapabilityStatus.UNAVAILABLE -> "服务端不可用"
        hardwareVolume.canWrite -> "服务端状态新鲜"
        hardwareVolume.displayVolumePercent == null -> "音量状态未知"
        else -> "音量状态已过期或不可控制"
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val naturalOutputWidth = with(density) {
        textMeasurer.measure(
            text = outputInfo,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        ).size.width.toDp()
    }
    val naturalStatusWidth = with(density) {
        textMeasurer.measure(
            text = outputStatus,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        ).size.width.toDp()
    }
    val naturalPanelWidth = maxOf(
        200.dp,
        28.dp + 20.dp + 10.dp + naturalOutputWidth + 10.dp + naturalStatusWidth,
        28.dp + 20.dp + 10.dp + 96.dp + 10.dp + 24.dp,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maximumPanelWidth = minOf(
            maxWidth * 0.92f,
            520.dp,
            (maxWidth - 96.dp).coerceAtLeast(200.dp),
        )
        val panelWidth = naturalPanelWidth.coerceAtMost(maximumPanelWidth)
        val positionProvider = remember(density) {
            AnchoredDropdownPositionProvider(with(density) { 48.dp.roundToPx() })
        }

        // Match the queue-row ellipsis popup: an attached sub-panel window anchored to the
        // measured trigger. Do not use Dialog here; its independent application window changes
        // system-bar insets/appearance and briefly exposes its transparent window during exit.
        PopupMotionHost(visible = visible, anchorState = anchorState) { surfaceMotionModifier, _ ->
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = onDismiss,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                Surface(
                    modifier = surfaceMotionModifier.testTag("player-dac-volume-panel"),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.94f),
                    tonalElevation = 0.dp,
                    shadowElevation = 7.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .width(panelWidth)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .testTag("dac-output-info"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PlayerGlyph(
                                kind = PlayerGlyphKind.OUTPUT,
                                tint = Color(0xFF555555),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = outputInfo,
                                modifier = Modifier.weight(1f).testTag("dac-output-format"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF252525),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = outputStatus,
                                modifier = Modifier.testTag("dac-output-status"),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF555555),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("dac-volume-row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PlayerGlyph(
                                kind = PlayerGlyphKind.VOLUME,
                                tint = Color(0xFF555555),
                                modifier = Modifier.size(20.dp),
                            )
                            val trackProgress = (sliderDraft.previewPercent ?: volume?.toFloat() ?: 0f) / 100f
                            if (sliderEnabled) {
                                Box(
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(Color(0xFFD8D8D8))
                                            .testTag("dac-volume-track"),
                                    ) {
                                        if (trackProgress > 0f) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(trackProgress.coerceIn(0f, 1f))
                                                    .background(Color(0xFFB76520)),
                                            )
                                        }
                                    }
                                    Slider(
                                        value = sliderDraft.previewPercent ?: volume!!.toFloat(),
                                        onValueChange = { requested ->
                                            sliderDraft = sliderDraft.preview(requested, currentCanonicalTuple.value)
                                        },
                                        onValueChangeFinished = {
                                            val target = sliderDraft.targetForRelease(
                                                current = currentCanonicalTuple.value,
                                                canWrite = currentCanWrite.value && currentCommandPhase.value != HardwareVolumeCommandPhase.UNKNOWN,
                                            )
                                            sliderDraft = HardwareVolumeSliderDraft()
                                            target?.let(onVolumeCommit)
                                        },
                                        enabled = true,
                                        valueRange = 0f..100f,
                                        steps = 99,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.Transparent,
                                            activeTrackColor = Color.Transparent,
                                            inactiveTrackColor = Color.Transparent,
                                            activeTickColor = Color.Transparent,
                                            inactiveTickColor = Color.Transparent,
                                            disabledThumbColor = Color.Transparent,
                                            disabledActiveTrackColor = Color.Transparent,
                                            disabledInactiveTrackColor = Color.Transparent,
                                            disabledActiveTickColor = Color.Transparent,
                                            disabledInactiveTickColor = Color.Transparent,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("dac-volume-slider")
                                            .semantics {
                                                contentDescription = buildString {
                                                    append("MPD 硬件音量，服务端确认值 $volume")
                                                    previewVolume?.let { append("，本地拖动预览 $it，松手后提交") }
                                                    if (commandPending) append("，正在等待服务端读回")
                                                    if (commandState.phase == HardwareVolumeCommandPhase.FAILED) append("，上次设置失败")
                                                }
                                            },
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(Color(0xFFD8D8D8))
                                        .testTag("dac-volume-slider")
                                        .semantics {
                                            testTag = "dac-volume-slider"
                                            contentDescription = buildString {
                                                append("硬件音量，只读，")
                                                append(volume?.let { "服务端确认值 $it" } ?: "数值未知")
                                                append("，$volumeStatus")
                                                commandState.message?.let { append("，$it") }
                                            }
                                            volume?.let {
                                                progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..100f)
                                            }
                                            disabled()
                                        },
                                ) {
                                    if (volume != null && volume > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(volume / 100f)
                                                .background(Color(0xFFB76520)),
                                        )
                                    }
                                }
                            }
                            if (commandPending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp).testTag("dac-volume-pending"),
                                    strokeWidth = 1.5.dp,
                                )
                            } else if (commandState.phase == HardwareVolumeCommandPhase.FAILED) {
                                Text(
                                    text = "!",
                                    modifier = Modifier.testTag("dac-volume-error").semantics {
                                        contentDescription = commandState.message ?: "硬件音量设置失败"
                                    },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else if (commandState.phase == HardwareVolumeCommandPhase.UNKNOWN) {
                                Text(
                                    text = "?",
                                    modifier = Modifier.testTag("dac-volume-unknown").semantics {
                                        contentDescription = commandState.message ?: "硬件音量设置结果未知，需要使用原操作重试"
                                    },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = previewVolume?.toString() ?: volume?.toString() ?: "—",
                                modifier = Modifier
                                    .widthIn(min = 24.dp)
                                    .testTag("dac-volume-value")
                                    .semantics {
                                        contentDescription = when {
                                            previewVolume != null -> "硬件音量本地预览值 $previewVolume，尚未提交；服务端确认值 ${volume ?: "未知"}"
                                            else -> volume?.let { "服务端确认硬件音量 $it" } ?: "服务端确认硬件音量未知"
                                        }
                                    },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (previewVolume != null) Color(0xFFB76520) else Color(0xFF252525),
                                maxLines = 1,
                            )
                        }

                    }
                }
            }
        }
    }
}

private fun buildDacOutputLine(output: OutputPresentation): String = buildList {
    add("DAC")
    if (output.observationStatus == OutputObservationStatus.FRESH) {
        output.dacFormat?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
        output.dacSampleRateHz?.takeIf { it > 0 }?.let { hz ->
            val khz = hz / 1000.0
            val value = if (khz % 1.0 == 0.0) khz.toInt().toString() else String.format(Locale.ROOT, "%.1f", khz)
            add("$value kHz")
        }
        output.dacBitDepth?.takeIf { it > 0 }?.let { add("$it bit") }
        output.dacChannels?.takeIf { it > 0 }?.let { add("$it ch") }
    }
}.joinToString(" · ")

private fun DacOutputStatus.toChineseLabel(): String = when (this) {
    DacOutputStatus.ACTIVE -> "已启用"
    DacOutputStatus.INACTIVE -> "未启用"
    DacOutputStatus.UNAVAILABLE -> "不可用"
    DacOutputStatus.STALE -> "已过期"
    DacOutputStatus.UNKNOWN -> "未知"
    DacOutputStatus.ERROR -> "错误"
}
