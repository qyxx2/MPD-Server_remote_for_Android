package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto

/** Read-only song row projection. [key] remains stable for rows without a song_id. */
data class LibrarySongRowModel(
    val key: String,
    val songId: String?,
    val song: SongDto?,
) {
    val isPlayable: Boolean
        get() = song?.songId != null && song.availabilityStatus == AvailabilityStatus.AVAILABLE
}

object LibraryProjection {
    val categories: List<LibraryCategory> = LibraryCategory.entries

    /** Preserve every server row and its order; do not sort or filter availability on the client. */
    fun songRows(songs: List<SongDto>): List<LibrarySongRowModel> {
        val occurrences = mutableMapOf<String, Int>()
        return songs.map { song ->
            val id = song.songId
            val baseKey = id?.let { "song:$it" } ?: "file:${song.fileUri}"
            val occurrence = (occurrences[baseKey] ?: 0) + 1
            occurrences[baseKey] = occurrence
            LibrarySongRowModel(
                key = if (occurrence == 1) baseKey else "$baseKey#$occurrence",
                songId = id,
                song = song,
            )
        }
    }

    /** Resolve an ordered server-owned ID list; duplicate occurrences remain distinct and missing IDs stay visible. */
    fun songIds(ids: List<String>, songsById: Map<String, SongDto>): List<LibrarySongRowModel> {
        val occurrences = mutableMapOf<String, Int>()
        return ids.map { id ->
            val occurrence = (occurrences[id] ?: 0) + 1
            occurrences[id] = occurrence
            LibrarySongRowModel(
                key = if (occurrence == 1) "song:$id" else "song:$id#$occurrence",
                songId = id,
                song = songsById[id]?.takeIf { it.songId == id },
            )
        }
    }
}
