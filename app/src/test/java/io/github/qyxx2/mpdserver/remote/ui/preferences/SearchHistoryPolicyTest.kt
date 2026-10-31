package io.github.qyxx2.mpdserver.remote.ui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryPolicyTest {
    @Test
    fun recordsLatestFirstDeduplicatesAndKeepsAtMostTenEntries() {
        val recent = SearchHistoryPolicy.record(
            history = (1..10).map { "query-$it" },
            query = " query-5 ",
        )
        assertEquals("query-5", recent.first())
        assertEquals(10, recent.size)
        assertEquals(1, recent.count { it == "query-5" })
        assertEquals("query-10", recent.last())

        val appended = SearchHistoryPolicy.record(recent, "new query")
        assertEquals("new query", appended.first())
        assertEquals(10, appended.size)
        assertTrue("query-10" !in appended)
    }

    @Test
    fun blankQueriesDoNotEnterHistory() {
        assertEquals(listOf("existing"), SearchHistoryPolicy.record(listOf("existing"), "  \n "))
    }
}
