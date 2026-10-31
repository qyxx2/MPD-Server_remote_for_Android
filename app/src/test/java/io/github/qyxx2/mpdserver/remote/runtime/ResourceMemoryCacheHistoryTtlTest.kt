package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceMemoryCacheHistoryTtlTest {
    @Test
    fun historyEntryReportsItsRemainingMonotonicTtlWithoutExtendingIt() = runBlocking {
        var now = 10_000L
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner, historyTtlMillis = 60_000L, clockMillis = { now })
        val historyKey = ResourceCacheKey.get<String>("/api/history")
        val libraryKey = ResourceCacheKey.get<String>("/api/library/songs?sort_by=modified_at&sort_order=desc")
        try {
            cache.updateIdentity(
                ResourceSessionIdentity("http://server.example", 1, "epoch-1"),
                ResourceWatermarks(3, 5, "history-fingerprint"),
                isFresh = true,
            )
            assertNull(cache.remainingTtlMillis(historyKey))
            cache.readResource(historyKey, ResourceDependency.HISTORY) {
                ApiResult.Success("persistent history", 200)
            }
            assertEquals(60_000L, cache.remainingTtlMillis(historyKey))
            now += 25_000L
            assertEquals(35_000L, cache.remainingTtlMillis(historyKey))
            now += 35_000L
            assertEquals(0L, cache.remainingTtlMillis(historyKey))
            assertNull(cache.remainingTtlMillis(libraryKey))
        } finally {
            cache.close()
            owner.cancel()
        }
    }
}
