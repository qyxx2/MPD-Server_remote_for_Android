package io.github.qyxx2.mpdserver.remote.ui.search

import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchGroupKindDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryCategory
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryRoutes
import io.github.qyxx2.mpdserver.remote.ui.library.LibrarySongRowModel
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryProjection

data class SearchGroupProjection(
    val kind: SearchGroupKindDto,
    val sourceId: String,
    val name: String,
    val songCount: Int,
    val songs: List<LibrarySongRowModel>,
)

data class SearchProjection(
    val groups: List<SearchGroupProjection>,
    /** Top-level Song IDs not already represented in a group, in server response order. */
    val songs: List<LibrarySongRowModel>,
) {
    companion object {
        /** Preserve server order and de-duplicate globally on first appearance. */
        fun from(response: SearchResultsDto, songsById: Map<String, SongDto>): SearchProjection {
            val seen = linkedSetOf<String>()
            val groups = response.groups.map { group ->
                SearchGroupProjection(
                    kind = group.kind,
                    sourceId = group.sourceId,
                    name = group.name,
                    songCount = group.songIds.size,
                    songs = uniqueRows(group.songIds, seen, songsById),
                )
            }
            return SearchProjection(groups, uniqueRows(response.songIds, seen, songsById))
        }

        fun orderedSongIds(response: SearchResultsDto): List<String> {
            val seen = linkedSetOf<String>()
            response.groups.forEach { group -> group.songIds.forEach(seen::add) }
            response.songIds.forEach(seen::add)
            return seen.toList()
        }

        fun categoryRoute(kind: SearchGroupKindDto, sourceId: String): String {
            val category = when (kind) {
                SearchGroupKindDto.ALBUMS -> LibraryCategory.ALBUMS
                SearchGroupKindDto.ARTISTS -> LibraryCategory.ARTISTS
                SearchGroupKindDto.GENRES -> LibraryCategory.GENRES
                SearchGroupKindDto.YEARS -> LibraryCategory.YEARS
                SearchGroupKindDto.TAGS -> LibraryCategory.TAGS
            }
            return LibraryRoutes.detail(category, sourceId)
        }

        private fun uniqueRows(
            ids: List<String>,
            seen: MutableSet<String>,
            songsById: Map<String, SongDto>,
        ): List<LibrarySongRowModel> =
            LibraryProjection.songIds(ids.filter { seen.add(it) }, songsById)
    }
}
