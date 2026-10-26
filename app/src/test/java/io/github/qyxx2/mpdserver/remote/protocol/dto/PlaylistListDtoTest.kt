package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.decodeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistListDtoTest {
    @Test
    fun decodesObservedItemsAndCountEnvelope() {
        val body = """
            {
              "items": [
                {
                  "playlist_id": "playlist-123",
                  "name": "Task2 validation",
                  "created_at": "2026-10-27T00:00:00Z",
                  "updated_at": "2026-10-27T00:00:00Z",
                  "is_system": false,
                  "song_ids": []
                }
              ],
              "count": 1
            }
        """.trimIndent()

        val result = HttpResult.Body(200, "application/json", body.toByteArray()).decodeJson<PlaylistListDto>()

        assertTrue("The live playlist envelope must decode as a typed success", result is ApiResult.Success)
        val payload = (result as ApiResult.Success).value
        assertEquals(1, payload.count)
        assertEquals(1, payload.items.size)
        assertEquals("playlist-123", payload.items.single().playlistId)
        assertEquals("Task2 validation", payload.items.single().name)
        assertEquals(false, payload.items.single().isSystem)
    }

    @Test
    fun decodesEmptyLivePlaylistEnvelope() {
        val result = HttpResult.Body(
            200,
            "application/json",
            """{"items":[],"count":0}""".toByteArray(),
        ).decodeJson<PlaylistListDto>()

        assertTrue(result is ApiResult.Success)
        val payload = (result as ApiResult.Success).value
        assertEquals(0, payload.count)
        assertTrue(payload.items.isEmpty())
    }
}
