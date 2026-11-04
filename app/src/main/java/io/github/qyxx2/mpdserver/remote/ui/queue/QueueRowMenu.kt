package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import io.github.qyxx2.mpdserver.remote.ui.components.AnchoredDropdownPositionProvider
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionAnchorState
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionHost
import io.github.qyxx2.mpdserver.remote.ui.components.rememberPopupMotionAnchorState
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.ui.components.dialogSurfaceColor
import io.github.qyxx2.mpdserver.remote.ui.components.MotionAlertDialog

private const val QUEUE_MENU_SURFACE_ALPHA = 0.94f
private const val QUEUE_MENU_CORNER_RADIUS_DP = 18f
private const val QUEUE_MENU_SHADOW_ELEVATION_DP = 7f
private const val QUEUE_MENU_TONAL_ELEVATION_DP = 0f

internal val QueueMenuSurfaceAlphaKey = SemanticsPropertyKey<Float>("QueueMenuSurfaceAlpha")
internal val QueueMenuCornerRadiusKey = SemanticsPropertyKey<Float>("QueueMenuCornerRadiusDp")
internal val QueueMenuShadowElevationKey = SemanticsPropertyKey<Float>("QueueMenuShadowElevationDp")
internal val QueueMenuTonalElevationKey = SemanticsPropertyKey<Float>("QueueMenuTonalElevationDp")

internal enum class QueueToolDialog { CLEAR, SAVE_PLAYLIST }

/** A compact menu hosted beside the row's three-dot button, rather than a window-centered dialog. */
@Composable
internal fun QueueRowMenu(
    item: QueueItemDto?,
    title: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onMoveNext: () -> Unit,
    onMoveToEnd: () -> Unit,
    onRemove: () -> Unit,
    expanded: Boolean = item != null,
    anchorState: PopupMotionAnchorState = rememberPopupMotionAnchorState(),
    onDismissComplete: () -> Unit = {},
) {
    if (item == null) return
    val presentation = QueueMenuPresentation.from(item, enabled)
    val menuSurface = Color.White.copy(alpha = QUEUE_MENU_SURFACE_ALPHA)
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        AnchoredDropdownPositionProvider(with(density) { 48.dp.roundToPx() })
    }
    PopupMotionHost(
        visible = expanded,
        anchorState = anchorState,
        onDismissComplete = onDismissComplete,
    ) { surfaceMotionModifier, _ ->
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
                modifier = surfaceMotionModifier
                    .semantics {
                        this[QueueMenuSurfaceAlphaKey] = QUEUE_MENU_SURFACE_ALPHA
                        this[QueueMenuCornerRadiusKey] = QUEUE_MENU_CORNER_RADIUS_DP
                        this[QueueMenuShadowElevationKey] = QUEUE_MENU_SHADOW_ELEVATION_DP
                        this[QueueMenuTonalElevationKey] = QUEUE_MENU_TONAL_ELEVATION_DP
                    }
                    .testTag("queue-row-menu-dialog"),
                shape = RoundedCornerShape(QUEUE_MENU_CORNER_RADIUS_DP.dp),
                color = menuSurface,
                tonalElevation = QUEUE_MENU_TONAL_ELEVATION_DP.dp,
                shadowElevation = QUEUE_MENU_SHADOW_ELEVATION_DP.dp,
            ) {
        val titleGapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
        Layout(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("queue-row-menu-panel"),
            content = {
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .testTag("queue-menu-action-column"),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (QueueMenuAction.PLAY_NEXT in presentation.actions) {
                        QueueRowMenuAction(
                            id = "next",
                            label = "移为下一首",
                            glyph = QueueRowMenuGlyph.NEXT,
                            enabled = presentation.enabled,
                            onClick = onMoveNext,
                        )
                    }
                    if (QueueMenuAction.ADD_TO_END in presentation.actions) {
                        QueueRowMenuAction(
                            id = "add",
                            label = "移到队尾",
                            glyph = QueueRowMenuGlyph.ADD_TO_END,
                            enabled = presentation.enabled,
                            onClick = onMoveToEnd,
                        )
                    }
                    if (QueueMenuAction.REMOVE in presentation.actions) {
                        QueueRowMenuAction(
                            id = "remove",
                            label = "删除此条目",
                            glyph = QueueRowMenuGlyph.REMOVE,
                            enabled = presentation.enabled,
                            onClick = onRemove,
                        )
                    }
                    QueueRowMenuAction(
                        id = "cancel",
                        label = "取消",
                        glyph = QueueRowMenuGlyph.CANCEL,
                        enabled = true,
                        onClick = onDismiss,
                    )
                }
                Text(
                    text = title.ifBlank { item.songId },
                    modifier = Modifier.testTag("queue-menu-title"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            measurePolicy = QueueRowMenuMeasurePolicy(titleGapPx),
        )
            }
        }
    }
}

/**
 * Popup intrinsic width comes from the action column only. The title is measured after that
 * width is known, so a long song name can only ellipsize and cannot widen the popup.
 */
private class QueueRowMenuMeasurePolicy(
    private val titleGapPx: Int,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val actions = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val title = measurables[1].measure(
            Constraints(
                minWidth = actions.width,
                maxWidth = actions.width,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            ),
        )
        val contentWidth = maxOf(actions.width, title.width)
        val contentHeight = title.height + titleGapPx + actions.height
        return layout(contentWidth, contentHeight) {
            title.placeRelative(0, 0)
            actions.placeRelative(0, title.height + titleGapPx)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables[0].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val actionWidth = measurables[0].minIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
        return measurables[0].minIntrinsicHeight(actionWidth) + titleGapPx + measurables[1].minIntrinsicHeight(actionWidth)
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int {
        val actionWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
        return measurables[0].maxIntrinsicHeight(actionWidth) + titleGapPx + measurables[1].maxIntrinsicHeight(actionWidth)
    }
}

private enum class QueueRowMenuGlyph { NEXT, ADD_TO_END, REMOVE, CANCEL }

@Composable
private fun QueueRowMenuAction(
    id: String,
    label: String,
    glyph: QueueRowMenuGlyph,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val textColor = Color(0xFF252525)
    val iconColor = Color(0xFF555555)
    val itemAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("queue-menu-$id")
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(22.dp).testTag("queue-menu-icon-column-$id"),
            contentAlignment = Alignment.Center,
        ) {
            QueueRowMenuGlyphView(
                glyph = glyph,
                tint = iconColor.copy(alpha = itemAlpha),
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.testTag("queue-menu-$id-label"),
            color = textColor.copy(alpha = itemAlpha),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QueueRowMenuGlyphView(
    glyph: QueueRowMenuGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(
            width = 1.9.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        fun drawIconLine(startX: Float, startY: Float, endX: Float, endY: Float) {
            drawLine(
                color = tint,
                start = Offset(startX * sx, startY * sy),
                end = Offset(endX * sx, endY * sy),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }

        when (glyph) {
            QueueRowMenuGlyph.NEXT -> {
                val path = Path().apply {
                    moveTo(3f * sx, 3f * sy)
                    lineTo(16f * sx, 12f * sy)
                    lineTo(3f * sx, 21f * sy)
                    close()
                }
                drawPath(path, tint)
                drawIconLine(21f, 3f, 21f, 21f)
            }
            QueueRowMenuGlyph.ADD_TO_END -> {
                listOf(5f, 11f, 17f).forEach { y -> drawIconLine(3f, y, 14f, y) }
                drawIconLine(19f, 12f, 19f, 22f)
                drawIconLine(14f, 17f, 24f, 17f)
            }
            QueueRowMenuGlyph.REMOVE -> {
                drawIconLine(5f, 7f, 19f, 7f)
                drawIconLine(9f, 4f, 15f, 4f)
                val bin = Path().apply {
                    moveTo(7f * sx, 8f * sy)
                    lineTo(8.5f * sx, 21f * sy)
                    lineTo(15.5f * sx, 21f * sy)
                    lineTo(17f * sx, 8f * sy)
                    close()
                }
                drawPath(bin, tint, style = stroke)
                drawIconLine(11f, 11f, 11f, 18f)
                drawIconLine(14f, 11f, 14f, 18f)
            }
            QueueRowMenuGlyph.CANCEL -> {
                drawIconLine(5f, 5f, 19f, 19f)
                drawIconLine(19f, 5f, 5f, 19f)
            }
        }
    }
}

@Composable
internal fun QueueToolsDialog(
    dialog: QueueToolDialog?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSave: (String) -> Unit,
    saveFeedback: String? = null,
    saveAlreadyConfirmed: Boolean = false,
    saveReadbackOnlyRetry: Boolean = false,
    onRetrySaveReadback: () -> Unit = {},
    clearAnchorState: PopupMotionAnchorState = rememberPopupMotionAnchorState(),
    saveAnchorState: PopupMotionAnchorState = rememberPopupMotionAnchorState(),
) {
    var name by remember { mutableStateOf("") }
    var retainedDialog by remember { mutableStateOf<QueueToolDialog?>(null) }
    LaunchedEffect(dialog) {
        if (dialog != null) {
            retainedDialog = dialog
            if (dialog == QueueToolDialog.SAVE_PLAYLIST) name = ""
        }
    }
    val renderedDialog = dialog ?: retainedDialog
    val isClear = renderedDialog == QueueToolDialog.CLEAR
    MotionAlertDialog(
        visible = dialog != null,
        onDismissRequest = onDismiss,
        anchorState = if (isClear) clearAnchorState else saveAnchorState,
        containerColor = dialogSurfaceColor(),
        dialogTitle = if (isClear) "清空待播" else "保存为 Playlist",
        modifier = Modifier.testTag(if (isClear) "queue-clear-dialog" else "queue-save-dialog"),
        title = {
            Text(
                if (isClear) "清空待播" else "保存为 Playlist",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            when (renderedDialog) {
                QueueToolDialog.CLEAR -> Text(
                    "清空服务器定义的待播项目，保留当前与已播放项目。AutoPlay 之后可能补充候选。此操作不会停止播放。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                QueueToolDialog.SAVE_PLAYLIST -> Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist 名称") },
                        singleLine = true,
                        enabled = enabled && !saveAlreadyConfirmed,
                        modifier = Modifier.fillMaxWidth().testTag("queue-playlist-name"),
                    )
                    Text(
                        "保存时由服务器读取完整待播集合，包括未显示的候选；不包含当前与已播放项目。重复歌曲由服务器校验。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    saveFeedback?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp).testTag("queue-save-feedback"),
                        )
                    }
                }
                null -> Unit
            }
        },
        confirmButton = {
            when (renderedDialog) {
                QueueToolDialog.CLEAR -> TextButton(
                    enabled = enabled,
                    onClick = onClear,
                    modifier = Modifier.testTag("queue-clear-confirm"),
                ) { Text("确认清空待播") }
                QueueToolDialog.SAVE_PLAYLIST -> TextButton(
                    enabled = if (saveAlreadyConfirmed) saveReadbackOnlyRetry else enabled && name.isNotBlank(),
                    onClick = {
                        if (saveAlreadyConfirmed) {
                            if (saveReadbackOnlyRetry) onRetrySaveReadback()
                        } else onSave(name)
                    },
                    modifier = Modifier.testTag("queue-save-confirm"),
                ) {
                    Text(
                        when {
                            saveAlreadyConfirmed && saveReadbackOnlyRetry -> "重试读取"
                            saveAlreadyConfirmed -> "等待原服务器"
                            else -> "保存"
                        },
                    )
                }
                null -> Unit
            }
        },
        dismissButton = if (renderedDialog == null) null else {
            {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(if (isClear) "queue-clear-cancel" else "queue-save-cancel"),
                ) { Text("取消") }
            }
        },
    )
}
