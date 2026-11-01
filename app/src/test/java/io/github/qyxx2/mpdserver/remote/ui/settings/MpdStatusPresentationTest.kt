package io.github.qyxx2.mpdserver.remote.ui.settings

import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpdStatusPresentationTest {
    @Test
    fun mpdDurationsFormatAsDaysHoursMinutesSeconds() {
        assertEquals("0天 0小时 0分 0秒", formatDurationSeconds(0.0))
        assertEquals("0天 0小时 0分 59秒", formatDurationSeconds(59.99))
        assertEquals("0天 0小时 1分 0秒", formatDurationSeconds(60.0))
        assertEquals("0天 1小时 0分 0秒", formatDurationSeconds(3600.0))
        assertEquals("1天 0小时 0分 0秒", formatDurationSeconds(86400.0))
        assertEquals("2天 3小时 4分 5秒", formatDurationSeconds(183845.9))
    }

    @Test
    fun invalidDurationAndMissingTimestampStayUnknown() {
        assertEquals("未知", formatDurationSeconds(null))
        assertEquals("未知", formatDurationSeconds(-0.1))
        assertEquals("未知", formatDurationSeconds(Double.NaN))
        assertEquals("未知", formatDurationSeconds(Double.POSITIVE_INFINITY))
        assertEquals("未知", formatEpochSeconds(null))
        assertEquals("未知", formatObservedAt(null))
        assertEquals("not-an-iso-date", formatObservedAt("not-an-iso-date"))
        assertTrue(formatEpochSeconds(1793520000L) != "未知")
        assertTrue(formatObservedAt("2026-11-01T06:30:00Z") != "未知")
    }

    @Test
    fun mpdStatusPresentationSeparatesMpdAndAndroidServerConnectivity() {
        val disconnected = MpdStatusPresentation.from(MpdStatusDto(connected = false))
        val connected = MpdStatusPresentation.from(MpdStatusDto(connected = true))
        val unknown = MpdStatusPresentation.from(MpdStatusDto(connected = null))

        assertEquals("未连接", disconnected.connectionLabel)
        assertEquals("已连接", connected.connectionLabel)
        assertEquals("未知", unknown.connectionLabel)
        assertEquals("未知", disconnected.version)
        assertNull(disconnected.versionSource)
    }
}
