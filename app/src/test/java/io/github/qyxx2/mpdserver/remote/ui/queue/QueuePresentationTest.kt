package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePresentationTest {
    @Test
    fun cacheKeyIgnoresPlaybackPositionTicksButTracksQueueAndPlaybackSemantics() {
        val original = PlayerTestFixtures.state()
        val originalKey = QueuePresentationCacheKey.from(original)
        val playbackWithNewPosition = original.snapshot!!.wire.playback.toString()
            .replace("\"position\":12.5", "\"position\":13.5")
        val playbackTick = PlayerTestFixtures.state(
            playback = playbackWithNewPosition,
            capturedAt = "2026-10-29T10:00:02Z",
        )
        assertEquals(originalKey, QueuePresentationCacheKey.from(playbackTick))

        val queueRevisionChanged = PlayerTestFixtures.state(
            queue = PlayerTestFixtures.queueJson.replace("\"revision\":17", "\"revision\":18"),
        )
        assertFalse(originalKey == QueuePresentationCacheKey.from(queueRevisionChanged))

        val autoplayChanged = PlayerTestFixtures.state(
            playback = original.snapshot!!.wire.playback.toString()
                .replace("\"autoplay_enabled\":false", "\"autoplay_enabled\":true"),
        )
        assertFalse(originalKey == QueuePresentationCacheKey.from(autoplayChanged))

        val observedStateChanged = PlayerTestFixtures.state(
            observation = PlayerTestFixtures.observationJson
                .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\""),
        )
        assertFalse(originalKey == QueuePresentationCacheKey.from(observedStateChanged))
        assertFalse(originalKey == QueuePresentationCacheKey.from(
            PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE),
        ))
    }

    @Test
    fun manualPendingItemsTakePriorityAndPreserveOccurrenceIdentityAndPlayedOrder() {
        val state = stateWithQueue(
            items = listOf(
                item("current", "duplicate-song", 0, QueueSourceDto.MANUAL),
                item("manual-late", "duplicate-song", 3, QueueSourceDto.MANUAL),
                item("auto", "auto-song", 2, QueueSourceDto.AUTOPLAY),
                item("manual-first", "duplicate-song", 1, QueueSourceDto.MANUAL),
                item("played-old", "old-song", -3, QueueSourceDto.MANUAL),
                item("played-new", "new-song", -1, QueueSourceDto.MANUAL),
            ),
            autoplayEnabled = true,
        )

        val result = QueuePresentation.deriveQueue(state)

        assertEquals("current", result.current?.queueItemId)
        assertTrue(result.currentConfirmed)
        assertEquals(listOf("manual-first", "manual-late"), result.next.map { it.queueItemId })
        assertEquals(listOf("current", "manual-first", "manual-late"), result.visibleRows.map { it.queueItemId })
        assertEquals(listOf("played-new", "played-old"), result.played.map { it.queueItemId })
        assertEquals(3, result.visibleRows.count { it.songId == "duplicate-song" })
        assertEquals(QueuePlayedSource.SNAPSHOT, result.playedSource)
        assertEquals("正在播放", result.currentLabel)
    }

    @Test
    fun autoplayOnlyShowsItsFirstCandidateWhenEnabledAndCurrentExists() {
        val items = listOf(
            item("current", "song-current", 0, QueueSourceDto.MANUAL),
            item("auto-late", "song-late", 8, QueueSourceDto.AUTOPLAY),
            item("auto-first", "song-first", 2, QueueSourceDto.AUTOPLAY),
        )
        val enabled = QueuePresentation.deriveQueue(stateWithQueue(items, autoplayEnabled = true))
        val disabled = QueuePresentation.deriveQueue(stateWithQueue(items, autoplayEnabled = false))
        val noCurrent = QueuePresentation.deriveQueue(
            stateWithQueue(items.filterNot { it.position == 0 }, autoplayEnabled = true),
        )

        assertEquals(listOf("auto-first"), enabled.next.map { it.queueItemId })
        assertTrue(disabled.next.isEmpty())
        assertTrue(noCurrent.next.isEmpty())
    }

    @Test
    fun canonicalPlaybackWireShapeShowsOneAutoplayNextWhenNoManualPendingExists() {
        val state = PlayerTestFixtures.state(
            queue = queueJson(
                listOf(
                    item("current-auto", "song-current", 0, QueueSourceDto.AUTOPLAY),
                    item("next-auto", "song-next", 1, QueueSourceDto.AUTOPLAY),
                    item("later-auto", "song-later", 2, QueueSourceDto.AUTOPLAY),
                ),
            ),
            playback = """{"song_id":"song-current","state":"PAUSED","playback_context_id":"context-1","position_seconds":105.042,"autoplay_enabled":true,"updated_at":"2026-10-28T06:07:30.366157Z"}""",
            observation = PlayerTestFixtures.observationJson
                .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"current-auto\""),
        )

        val result = QueuePresentation.deriveQueue(state)

        assertEquals(listOf("next-auto"), result.next.map { it.queueItemId })
        assertEquals(listOf("current-auto", "next-auto"), result.visibleRows.map { it.queueItemId })
    }

    @Test
    fun staleSnapshotUsesOnlySuppliedPlayedFallbackAndDoesNotConfirmCurrent() {
        val stale = stateWithQueue(
            items = listOf(
                item("current", "song-current", 0, QueueSourceDto.MANUAL),
                item("snapshot-played", "stale-played", -1, QueueSourceDto.MANUAL),
            ),
            autoplayEnabled = false,
            freshness = SnapshotFreshness.STALE,
        )
        val fallback = listOf(
            item("fallback-old", "old", -5, QueueSourceDto.MANUAL),
            item("fallback-new", "new", -1, QueueSourceDto.MANUAL),
        )

        val result = QueuePresentation.deriveQueue(stale, playedFallback = fallback)

        assertFalse(result.currentConfirmed)
        assertTrue(result.currentLabel.contains("未确认"))
        assertEquals(listOf("fallback-new", "fallback-old"), result.played.map { it.queueItemId })
        assertEquals(QueuePlayedSource.FALLBACK, result.playedSource)
        assertEquals(listOf("current"), result.visibleRows.map { it.queueItemId })
    }

    @Test
    fun freshCanonicalPlayedListWinsOverFallbackAndCurrentStateNeedsBoundObservation() {
        val fresh = stateWithQueue(
            items = listOf(
                item("current", "song-current", 0, QueueSourceDto.MANUAL),
                item("canonical-played", "played", -1, QueueSourceDto.MANUAL),
            ),
            autoplayEnabled = false,
        )
        val result = QueuePresentation.deriveQueue(
            fresh,
            playedFallback = listOf(item("wrong-fallback", "other", -1, QueueSourceDto.MANUAL)),
        )

        assertEquals(QueuePlayedSource.SNAPSHOT, result.playedSource)
        assertEquals(listOf("canonical-played"), result.played.map { it.queueItemId })

        val mismatchedObservation = PlayerTestFixtures.observationJson
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"different-occurrence\"")
        val mismatched = QueuePresentation.deriveQueue(
            PlayerTestFixtures.state(
                queue = queueJson(listOf(item("occurrence-1", "song-1", 0, QueueSourceDto.MANUAL))),
                observation = mismatchedObservation,
            ),
        )
        assertFalse(mismatched.currentConfirmed)
        assertTrue(mismatched.currentLabel.contains("未确认"))
    }

    @Test
    fun confirmedPausedCurrentUsesPausedLabel() {
        val observation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"current\"")
            .replace("\"queue_item_id\":\"occurrence-1\"", "\"queue_item_id\":\"current\"")
        val state = PlayerTestFixtures.state(
            queue = queueJson(listOf(item("current", "song-1", 0, QueueSourceDto.MANUAL))),
            observation = observation,
            playback = """{"song_id":"song-1","state":"PAUSED","playback_context_id":"context-1","position_seconds":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}""",
        )

        val result = QueuePresentation.deriveQueue(state)

        assertTrue(result.currentConfirmed)
        assertEquals("已暂停", result.currentLabel)
    }

    @Test
    fun emptyOrMalformedQueueNeverInventsVisibleRows() {
        val empty = QueuePresentation.deriveQueue(stateWithQueue(emptyList(), autoplayEnabled = true))
        assertNull(empty.current)
        assertTrue(empty.visibleRows.isEmpty())
        assertTrue(empty.next.isEmpty())

        val malformed = QueuePresentation.deriveQueue(
            PlayerTestFixtures.state(queue = """{"revision":17,"items":"broken"}"""),
        )
        assertTrue(malformed.visibleRows.isEmpty())
        assertEquals(QueueSnapshotStatus.INVALID, malformed.snapshotStatus)
    }

    private fun stateWithQueue(
        items: List<QueueItemDto>,
        autoplayEnabled: Boolean,
        freshness: SnapshotFreshness = SnapshotFreshness.FRESH,
    ) = PlayerTestFixtures.state(
        queue = queueJson(items),
        observation = PlayerTestFixtures.observationJson.replace(
            "\"bound_queue_item_id\":\"occurrence-1\"",
            "\"bound_queue_item_id\":\"current\"",
        ),
        playback = """{"song_id":"song-1","state":"PLAYING","playback_context_id":"context-1","position_seconds":12.5,"autoplay_enabled":$autoplayEnabled,"updated_at":"2026-10-27T10:00:00Z"}""",
        freshness = freshness,
    )

    private fun queueJson(items: List<QueueItemDto>): String =
        """{"revision":17,"items":[${items.joinToString(",") { item ->
            """{"queue_item_id":"${item.queueItemId}","song_id":"${item.songId}","position":${item.position},"source":"${item.source.name}","playback_context_id":null}"""
        }}]}"""

    private fun item(id: String, songId: String, position: Int, source: QueueSourceDto) =
        QueueItemDto(id, songId, position, source, null)
}
