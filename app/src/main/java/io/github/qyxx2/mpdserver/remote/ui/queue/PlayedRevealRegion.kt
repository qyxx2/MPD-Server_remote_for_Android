package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * A single Queue item whose viewport height is exactly the live reveal extent. Rows are measured
 * at their natural height inside an unbounded child, while the outer viewport clips the fractional
 * final row. History rows are keyed by canonical Queue occurrence identity.
 */
@Composable
internal fun PlayedRevealRegion(
    rows: List<QueueItemDto>,
    revealedHeightPx: Float,
    cache: QueueSongCache,
    resourceIdentity: QueueResourceIdentity,
    actions: QueueActions,
    selectedMenuItem: QueueItemDto?,
    onSelectedMenuItem: (QueueItemDto?) -> Unit,
    onNaturalHeightMeasured: (heightPx: Float, measuredRowCount: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val height = with(density) { revealedHeightPx.coerceAtLeast(0f).toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .testTag("queue-played-reveal-region"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true)
                .onSizeChanged { onNaturalHeightMeasured(it.height.toFloat(), rows.size) },
        ) {
            if (revealedHeightPx > 0f) {
                rows.forEachIndexed { index, occurrence ->
                    key(occurrence.queueItemId) {
                        val entryFlow = remember(cache, resourceIdentity, occurrence.songId) {
                            cache.state.map { entries ->
                                entries[occurrence.songId]?.takeIf { it.identity == resourceIdentity }
                            }.distinctUntilChanged()
                        }
                        val entry by entryFlow.collectAsStateWithLifecycle(
                            initialValue = cache.currentEntry(occurrence.songId, resourceIdentity),
                        )
                        val menuTitle = entry?.song?.title?.takeIf(String::isNotBlank)
                            ?: occurrence.songId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("queue-history-row-${occurrence.queueItemId}"),
                        ) {
                            QueueOccurrenceRow(
                                occurrence = occurrence,
                                isCurrent = false,
                                currentLabel = "",
                                cacheEntry = entry,
                                menuEnabled = actions.canShowMenu(occurrence.queueItemId),
                                menuTitle = menuTitle,
                                menuExpanded = selectedMenuItem?.queueItemId == occurrence.queueItemId,
                                onPlay = { actions.play(occurrence.queueItemId, menuTitle) },
                                onMenu = { onSelectedMenuItem(occurrence) },
                                onDismissMenu = { onSelectedMenuItem(null) },
                                onMoveNext = {
                                    actions.move(occurrence.queueItemId, QueueMove.NEXT, menuTitle)
                                    onSelectedMenuItem(null)
                                },
                                onMoveToEnd = {
                                    actions.move(occurrence.queueItemId, QueueMove.ADD, menuTitle)
                                    onSelectedMenuItem(null)
                                },
                                onRemove = {
                                    actions.remove(occurrence.queueItemId, menuTitle)
                                    onSelectedMenuItem(null)
                                },
                            )
                            if (index < rows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("queue-history-divider-${occurrence.queueItemId}"),
                                    thickness = 0.75.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
