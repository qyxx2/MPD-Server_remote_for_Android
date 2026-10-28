package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import io.github.qyxx2.mpdserver.remote.ui.player.ArtworkReferenceStatus
import io.github.qyxx2.mpdserver.remote.ui.player.LyricsResourceStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerSongPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPresentationTest {
    @Test
    fun resourceStatusDistinguishesMissingReadErrorPlainTextAndUnsupportedFormat() {
        assertEquals(LyricsDisplayStatus.MISSING, presentLyrics(song(lyrics = null, status = LyricsResourceStatus.MISSING)).status)
        assertEquals(LyricsDisplayStatus.READ_ERROR, presentLyrics(song(lyrics = null, status = LyricsResourceStatus.READ_ERROR)).status)
        assertEquals(LyricsDisplayStatus.PLAIN_TEXT, presentLyrics(song(lyrics = "可自由查看", format = "text")).status)
        assertEquals(LyricsDisplayStatus.NO_READABLE_CUES, presentLyrics(song(lyrics = "[00:99]坏时间轴", format = "lrc")).status)
        assertEquals(LyricsDisplayStatus.UNSUPPORTED_FORMAT, presentLyrics(song(lyrics = "保持原文", format = "plain")).status)
        assertEquals(LyricsDisplayStatus.STATUS_UNKNOWN, presentLyrics(song(lyrics = null, status = LyricsResourceStatus.UNKNOWN)).status)
        assertEquals(LyricsDisplayStatus.SONG_UNCONFIRMED, presentLyrics(null).status)
    }

    @Test
    fun readErrorKeepsServerFallbackTextAndSourceWithoutHidingTheError() {
        val raw = "[00:37]服务端回退歌词"
        val value = presentLyrics(
            song(
                lyrics = raw,
                format = "text",
                source = "embedded",
                status = LyricsResourceStatus.READ_ERROR,
            ),
        )

        assertEquals(LyricsDisplayStatus.READ_ERROR, value.status)
        assertEquals(raw, value.document.text)
        assertTrue(value.document.synchronized)
        assertEquals(listOf(LyricsCue(37.0, "服务端回退歌词")), value.document.cues)
        assertEquals("embedded", value.source)
        assertEquals(LyricsResourceStatus.READ_ERROR, value.resourceStatus)
    }

    @Test
    fun embeddedTimecodedTextAndPlainEmbeddedTextFollowReturnedFormatRatherThanFileSource() {
        val timecoded = presentLyrics(
            song(lyrics = "[00:37]内嵌时间轴", format = "text", source = "embedded"),
        )
        assertEquals(LyricsDisplayStatus.SYNCHRONIZED, timecoded.status)
        assertTrue(timecoded.document.synchronized)
        assertEquals("embedded", timecoded.source)

        val plain = presentLyrics(song(lyrics = "内嵌普通文本", format = "text", source = "embedded"))
        assertEquals(LyricsDisplayStatus.PLAIN_TEXT, plain.status)
        assertFalse(plain.document.synchronized)
        assertEquals("内嵌普通文本", plain.document.text)
    }

    @Test
    fun unknownPlaybackPositionNeverCreatesAnActiveCue() {
        val document = parseLyrics("[00:37]第一行\n[00:39]第二行", "lrc")
        assertTrue(document.synchronized)
        assertTrue(activeCueIndexes(document, null).isEmpty())
        assertEquals(listOf(0), activeCueIndexes(document, 37.0))
    }

    @Test
    fun manualScrollingPausesImmediatelyAndReturnsToLatestCueAfterThreeIdleSeconds() {
        var state = LyricsFollowReducer.initialState()
        state = LyricsFollowReducer.onGestureStart(state, atMillis = 100)
        assertFalse(state.following)
        assertTrue(state.gestureActive)

        state = LyricsFollowReducer.onGestureEnd(state, atMillis = 500)
        assertFalse(state.following)
        assertEquals(500L, state.lastInteractionAtMillis)
        assertFalse(LyricsFollowReducer.onTimeout(state, atMillis = 3499).returnToActiveCue)

        val timeout = LyricsFollowReducer.onTimeout(state, atMillis = 3500)
        assertTrue(timeout.returnToActiveCue)
        assertTrue(timeout.state.following)
        assertNull(timeout.state.lastInteractionAtMillis)
    }

    @Test
    fun continuedScrollResetsDeadlineAndActiveGestureBlocksTimeout() {
        var state = LyricsFollowReducer.onGestureStart(LyricsFollowReducer.initialState(), 0)
        state = LyricsFollowReducer.onGestureEnd(state, 10)
        state = LyricsFollowReducer.onScroll(state, 2_000)
        assertFalse(LyricsFollowReducer.onTimeout(state, 4_999).returnToActiveCue)
        assertTrue(LyricsFollowReducer.onTimeout(state, 5_000).returnToActiveCue)

        state = LyricsFollowReducer.onGestureStart(state, 6_000)
        assertFalse(LyricsFollowReducer.onTimeout(state, 12_000).returnToActiveCue)
        state = LyricsFollowReducer.onGestureEnd(state, 12_100)
        assertFalse(LyricsFollowReducer.onTimeout(state, 15_099).returnToActiveCue)
        assertTrue(LyricsFollowReducer.onTimeout(state, 15_100).returnToActiveCue)
    }

    @Test
    fun selectionDefersResumeUntilSelectionEndsAndCueActivationRestoresFollowing() {
        var state = LyricsFollowReducer.onScroll(LyricsFollowReducer.initialState(), 100)
        state = LyricsFollowReducer.onTextSelectionChanged(state, selected = true, atMillis = 500)
        assertFalse(LyricsFollowReducer.onTimeout(state, 10_000).returnToActiveCue)

        state = LyricsFollowReducer.onTextSelectionChanged(state, selected = false, atMillis = 10_001)
        assertFalse(LyricsFollowReducer.onTimeout(state, 13_000).returnToActiveCue)
        assertTrue(LyricsFollowReducer.onTimeout(state, 13_001).returnToActiveCue)

        state = LyricsFollowReducer.onScroll(state, 20_000)
        state = LyricsFollowReducer.onCueActivated(state)
        assertTrue(state.following)
        assertNull(state.lastInteractionAtMillis)
    }

    @Test
    fun resourceChangeResetsScrollGestureAndSelectionState() {
        var state = LyricsFollowReducer.onGestureStart(LyricsFollowReducer.initialState(), 10)
        state = LyricsFollowReducer.onTextSelectionChanged(state, true, 20)
        state = LyricsFollowReducer.onResourceChanged()

        assertTrue(state.following)
        assertFalse(state.gestureActive)
        assertFalse(state.textSelected)
        assertNull(state.lastInteractionAtMillis)
    }

    private fun song(
        lyrics: String?,
        format: String? = "lrc",
        source: String? = "sidecar",
        status: LyricsResourceStatus = LyricsResourceStatus.AVAILABLE,
    ) = PlayerSongPresentation(
        songId = "song-lyrics-test",
        title = "歌词测试",
        fileUri = "file:///music/lyrics-test.flac",
        artists = listOf("测试艺术家"),
        albumId = "album-test",
        album = "测试专辑",
        albumArtists = emptyList(),
        trackNumber = 1,
        discNumber = 1,
        durationSeconds = 120.0,
        lyrics = lyrics,
        lyricsFormat = format,
        lyricsSource = source,
        lyricsStatus = status,
        rawLyricsStatus = when (status) {
            LyricsResourceStatus.AVAILABLE -> "available"
            LyricsResourceStatus.MISSING -> "missing"
            LyricsResourceStatus.READ_ERROR -> "read_error"
            LyricsResourceStatus.UNKNOWN -> "unknown"
        },
        artworkReferenceStatus = ArtworkReferenceStatus.MISSING,
    )
}
