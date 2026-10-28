package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test
    fun parsesLrcAndLegacyTextWithWebFractionAndTimestampSemantics() {
        val raw = "[ar:测试]\n[00:02.5][00:01]复唱\n来源补充说明\n[00:02:05]短小数\n[00:02.005]毫秒\n[00:03.123]末行"
        val doc = parseLyrics(raw, "LrC")

        assertEquals(raw, doc.text)
        assertTrue(doc.synchronized)
        assertEquals(
            listOf(
                LyricsCue(1.0, "复唱"),
                LyricsCue(2.005, "毫秒"),
                LyricsCue(2.05, "短小数"),
                LyricsCue(2.5, "复唱"),
                LyricsCue(3.123, "末行"),
            ),
            doc.cues,
        )
        assertEquals(listOf("来源补充说明"), doc.notes)
        assertEquals(0L, doc.offsetMs)

        val cachedEmbedded = parseLyrics("[00:37]旧资源时间轴", "TEXT")
        assertTrue(cachedEmbedded.synchronized)
        assertEquals(listOf(LyricsCue(37.0, "旧资源时间轴")), cachedEmbedded.cues)
    }

    @Test
    fun parsesUnlimitedMinutesValidSecondsMultipleTimesAndStableTies() {
        val doc = parseLyrics(
            "[00:02.12][00:01]复唱\n[00:02:120]同刻\n[00:02.1]较早\n[123:59]长分钟",
            "lrc",
        )

        assertEquals(
            listOf(
                LyricsCue(1.0, "复唱"),
                LyricsCue(2.1, "较早"),
                LyricsCue(2.12, "复唱"),
                LyricsCue(2.12, "同刻"),
                LyricsCue(7439.0, "长分钟"),
            ),
            doc.cues,
        )
        assertEquals(listOf(2, 3), activeCueIndexes(doc, 2.12))
        assertEquals(listOf(1), activeCueIndexes(doc, 2.119))
    }

    @Test
    fun activeCueUsesLatestTimestampOffsetAndNoCueBeforeFirstOrWithoutPosition() {
        val doc = parseLyrics("[offset:1000]\n[00:38]甲\n[00:38]乙\n[00:40]丙", "lrc")

        assertEquals(emptyList<Int>(), activeCueIndexes(doc, null))
        assertEquals(emptyList<Int>(), activeCueIndexes(doc, 36.999))
        assertEquals(listOf(0, 1), activeCueIndexes(doc, 37.0))
        assertEquals(listOf(0, 1), activeCueIndexes(doc, 38.999))
        assertEquals(listOf(2), activeCueIndexes(doc, 39.0))
        assertEquals(listOf(2), activeCueIndexes(doc, 120.0))
    }

    @Test
    fun ignoresInvalidTimestampsAndKeepsRawPlainTextWithoutInventingCues() {
        val raw = "[ar:作者]\n[00:99]坏秒\n[00:01.1234]坏小数\n普通文本 <b>不解释</b>"
        val doc = parseLyrics(raw, "text")

        assertEquals(raw, doc.text)
        assertFalse(doc.synchronized)
        assertTrue(doc.cues.isEmpty())
        assertEquals(listOf("[00:99]坏秒", "[00:01.1234]坏小数", "普通文本 <b>不解释</b>"), doc.notes)
        assertEquals(emptyList<Int>(), activeCueIndexes(doc, 100.0))

        val unsupported = parseLyrics("[00:01]不可同步", "plain")
        assertEquals("[00:01]不可同步", unsupported.text)
        assertFalse(unsupported.formatSupported)
        assertFalse(unsupported.synchronized)
        assertTrue(unsupported.cues.isEmpty())
    }

    @Test
    fun mirrorsWebOffsetParsingAndMetadataNoteFiltering() {
        val doc = parseLyrics(
            "[offset:-500]\n[offset:1000]\n[offset:1.5]\n[offset:999999999999999999999999]\n[ti:标题]\n[by:作者]\n[00:02]提前",
            "LRC",
        )

        assertEquals(1000L, doc.offsetMs)
        assertEquals(1, doc.cues.size)
        assertTrue(doc.notes.isEmpty())
        assertEquals(listOf(0), activeCueIndexes(doc, 1.0))

        val invalidOnly = parseLyrics("[offset:9007199254740992]\n[00:02]歌词", "lrc")
        assertEquals(0L, invalidOnly.offsetMs)
    }

    @Test
    fun correctedSeekTargetRejectsInvalidCapabilityAndOutOfRangeTargets() {
        val cue = LyricsCue(seconds = 40.0, text = "目标")
        assertEquals(39.5, cue.seekTargetSeconds(offsetMs = 500, durationSeconds = 180.0, seekEnabled = true)!!, 0.0)
        assertEquals(40.5, cue.seekTargetSeconds(offsetMs = -500, durationSeconds = 180.0, seekEnabled = true)!!, 0.0)
        assertNull(cue.seekTargetSeconds(offsetMs = 500, durationSeconds = 180.0, seekEnabled = false))
        assertNull(cue.seekTargetSeconds(offsetMs = 500, durationSeconds = null, seekEnabled = true))
        assertNull(LyricsCue(0.2, "越界").seekTargetSeconds(500, 100.0, true))
        assertNull(LyricsCue(101.0, "越界").seekTargetSeconds(0, 100.0, true))
        assertNull(cue.seekTargetSeconds(500, Double.NaN, true))
    }
}
