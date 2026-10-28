package io.github.qyxx2.mpdserver.remote.ui.player

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.ui.player.lyrics.LyricsDisplayStatus
import io.github.qyxx2.mpdserver.remote.ui.player.lyrics.LyricsFollowReducer
import io.github.qyxx2.mpdserver.remote.ui.player.lyrics.LyricsPresentation
import io.github.qyxx2.mpdserver.remote.ui.player.lyrics.activeCueIndexes
import io.github.qyxx2.mpdserver.remote.ui.player.lyrics.presentLyrics
import io.github.qyxx2.mpdserver.remote.ui.player.lyrics.seekTargetSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.max

/** Native lyrics view using the frozen Web parser and the same shared PlayerActions seek owner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsStage(
    song: PlayerSongPresentation?,
    resourceIdentity: String,
    positionSeconds: Double?,
    durationSeconds: Double?,
    seekEnabled: Boolean,
    onSeek: (Double) -> Unit,
    onShowArtwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(song) { presentLyrics(song) }
    val document = presentation.document
    val timed = document.formatSupported && document.synchronized
    var followState by remember(resourceIdentity) { mutableStateOf(LyricsFollowReducer.initialState()) }
    var selectionEndedAtMillis by remember(resourceIdentity) { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    var programmaticScroll by remember(resourceIdentity) { mutableStateOf(false) }
    val activeIndexes = activeCueIndexes(document, positionSeconds)
    val activeIndex = activeIndexes.firstOrNull()
    val accent = MaterialTheme.colorScheme.primary

    // Observe real scroll activity too (including fling), while suppressing our own follow scroll.
    LaunchedEffect(listState, resourceIdentity, timed) {
        if (!timed) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
                programmaticScroll,
            )
        }
            .distinctUntilChanged()
            .collect { (_, scrolling, isProgrammatic) ->
                if (scrolling && !isProgrammatic) {
                    val now = SystemClock.elapsedRealtime()
                    followState = LyricsFollowReducer.onScroll(
                        followState.copy(textSelected = false),
                        now,
                    )
                }
            }
    }

    // The timeout consults the latest reducer state and active cue on each recomposition.
    LaunchedEffect(
        resourceIdentity,
        timed,
        followState.following,
        followState.gestureActive,
        followState.textSelected,
        followState.lastInteractionAtMillis,
    ) {
        if (!timed || followState.following || followState.gestureActive || followState.textSelected) {
            return@LaunchedEffect
        }
        val lastInteraction = followState.lastInteractionAtMillis ?: return@LaunchedEffect
        val remaining = max(
            0L,
            LyricsFollowReducer.RETURN_DELAY_MILLIS -
                (SystemClock.elapsedRealtime() - lastInteraction),
        )
        delay(remaining)
        val transition = LyricsFollowReducer.onTimeout(followState, SystemClock.elapsedRealtime())
        followState = transition.state
    }

    // Position may change continuously, but manual browsing suspends this effect until follow resumes.
    LaunchedEffect(resourceIdentity, activeIndex, followState.following, timed) {
        if (!timed || !followState.following || activeIndex == null) return@LaunchedEffect
        programmaticScroll = true
        try {
            val viewportHeight = snapshotFlow { listState.layoutInfo.viewportSize.height }
                .first { it > 0 }
            listState.animateScrollToItem(
                activeIndex,
                scrollOffset = if (document.cues.size > 1) -(viewportHeight / 2) else 0,
            )
        } finally {
            programmaticScroll = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize().testTag("lyrics-stage"),
    ) {
        IconButton(
            onClick = onShowArtwork,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(48.dp)
                .testTag("lyrics-return-artwork")
                .semantics { contentDescription = "显示封面" },
        ) {
            PlayerGlyph(
                kind = PlayerGlyphKind.BACK,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, end = 8.dp, bottom = 12.dp)
                .testTag("lyrics-content-column"),
        ) {
            StatusLine(
                presentation = presentation,
                timed = timed,
                positionKnown = positionSeconds != null,
            )

            if (timed) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("lyrics-selection-container"),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("lyrics-timed-content")
                                .pointerInput(resourceIdentity) {
                                    awaitEachGesture {
                                        awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial,
                                        )
                                        val startedAt = SystemClock.elapsedRealtime()
                                        if (followState.textSelected) {
                                            followState = LyricsFollowReducer.onTextSelectionChanged(
                                                followState,
                                                selected = false,
                                                atMillis = startedAt,
                                            )
                                            selectionEndedAtMillis = startedAt
                                        }
                                        followState = LyricsFollowReducer.onGestureStart(followState, startedAt)
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                        } while (event.changes.any { it.pressed })
                                        val endedAt = SystemClock.elapsedRealtime()
                                        followState = LyricsFollowReducer.onGestureEnd(followState, endedAt)
                                        if (endedAt - startedAt >= 500L) {
                                            // Suppress the click synthesized after a long press, but
                                            // do not leave automatic-follow permanently disabled.
                                            selectionEndedAtMillis = endedAt
                                        }
                                    }
                                },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = document.cues,
                                key = { index, cue -> index.toString() + ":" + cue.seconds + ":" + cue.text },
                            ) { index, cue ->
                                val current = index in activeIndexes && positionSeconds != null
                                val target = cue.seekTargetSeconds(
                                    offsetMs = document.offsetMs,
                                    durationSeconds = durationSeconds,
                                    seekEnabled = seekEnabled,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = target != null) {
                                            val now = SystemClock.elapsedRealtime()
                                            if (followState.textSelected) return@clickable
                                            val selectionEndedAt = selectionEndedAtMillis
                                            if (selectionEndedAt != null && now - selectionEndedAt < 750L) {
                                                selectionEndedAtMillis = null
                                                return@clickable
                                            }
                                            selectionEndedAtMillis = null
                                            val currentTarget = cue.seekTargetSeconds(
                                                offsetMs = document.offsetMs,
                                                durationSeconds = durationSeconds,
                                                seekEnabled = seekEnabled,
                                            )
                                            if (currentTarget != null) {
                                                followState = LyricsFollowReducer.onCueActivated(followState)
                                                onSeek(currentTarget)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                        .semantics {
                                            if (target != null) role = Role.Button
                                            contentDescription = if (target != null) "跳转到歌词：" + cue.text else cue.text
                                        }
                                        .then(if (current) Modifier.testTag("lyrics-cue-active") else Modifier)
                                        .testTag("lyrics-cue-" + index),
                                ) {
                                    val cueStyle = (if (current) {
                                        MaterialTheme.typography.titleLarge.copy(
                                            fontSize = 34.sp,
                                            lineHeight = 40.sp,
                                        )
                                    } else {
                                        MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 28.sp,
                                            lineHeight = 34.sp,
                                        )
                                    }).copy(
                                        color = if (current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    LyricCueText(
                                        text = cue.text,
                                        style = cueStyle,
                                        modifier = Modifier.fillMaxWidth().testTag("lyrics-cue-text-" + index),
                                    )
                                }
                            }
                            itemsIndexed(
                                items = document.notes,
                                key = { index, line -> "note:" + index + ":" + line },
                            ) { index, note ->
                                Text(
                                    text = note,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .testTag("lyrics-note-" + index),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            } else {
                // Plain/unsupported lyrics deliberately have no follow timer, active row or cue action.
                val plain = document.text
                if (plain.isNotEmpty()) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .testTag("lyrics-plain-scroll"),
                        ) {
                            Text(
                                text = plain,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("lyrics-plain-text"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = statusEmptyText(presentation.status),
                            modifier = Modifier.testTag("lyrics-empty-state"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricCueText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val layout = remember(text, style, maxWidthPx, textMeasurer) {
            textMeasurer.measure(
                text = text,
                style = style,
                constraints = Constraints(maxWidth = maxWidthPx),
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
        }
        val firstLineEnd = if (layout.hasVisualOverflow && layout.lineCount >= 2) {
            layout.getLineEnd(0, visibleEnd = true).coerceIn(0, text.length)
        } else {
            text.length
        }
        val scrollingSecondLine = if (firstLineEnd in 1 until text.length) {
            text.substring(firstLineEnd).trimStart()
        } else {
            ""
        }

        if (scrollingSecondLine.isEmpty()) {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                style = style,
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text.substring(0, firstLineEnd).trimEnd(),
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = scrollingSecondLine,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(
    presentation: LyricsPresentation,
    timed: Boolean,
    positionKnown: Boolean,
) {
    val labels = buildList {
        when (presentation.status) {
            LyricsDisplayStatus.READ_ERROR -> add("歌词读取失败")
            LyricsDisplayStatus.NO_READABLE_CUES -> add("无法同步 · 未发现有效时间轴")
            LyricsDisplayStatus.UNSUPPORTED_FORMAT -> add("普通文本 · 不支持同步此格式")
            LyricsDisplayStatus.STATUS_UNKNOWN -> add("歌词状态未确认")
            LyricsDisplayStatus.PLAIN_TEXT -> add("普通文本 · 不同步")
            else -> Unit
        }
        if (timed && !positionKnown) add("播放位置未确认，暂不同步")
        presentation.source
            ?.takeUnless { it.equals("embedded", ignoreCase = true) }
            ?.let { add("来源：" + it) }
    }
    if (labels.isNotEmpty()) {
        Text(
            text = labels.joinToString(" · "),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 56.dp, bottom = 6.dp)
                .testTag("lyrics-status"),
            color = if (presentation.status == LyricsDisplayStatus.READ_ERROR) {
                MaterialTheme.colorScheme.error
            } else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun statusEmptyText(status: LyricsDisplayStatus): String = when (status) {
    LyricsDisplayStatus.SONG_UNCONFIRMED -> "当前曲目未确认"
    LyricsDisplayStatus.MISSING -> "暂无歌词"
    LyricsDisplayStatus.READ_ERROR -> "暂无可显示的歌词"
    LyricsDisplayStatus.STATUS_UNKNOWN -> "暂无可显示的歌词"
    LyricsDisplayStatus.NO_READABLE_CUES -> "无法同步 · 未发现有效时间轴"
    LyricsDisplayStatus.UNSUPPORTED_FORMAT -> "不支持同步此歌词格式"
    LyricsDisplayStatus.PLAIN_TEXT -> "暂无歌词"
    LyricsDisplayStatus.SYNCHRONIZED -> "暂无可显示的歌词"
}
