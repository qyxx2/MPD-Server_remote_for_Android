package io.github.qyxx2.mpdserver.remote.ui.library

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Ordered Library root entries. The enum order is a product contract, not a server sort. */
enum class LibraryCategory(
    val pathSegment: String,
    val label: String,
    val glyph: String,
    val hasDetail: Boolean,
) {
    SONGS("songs", "歌曲", "♫", false),
    PLAYLISTS("playlists", "播放列表", "≋", true),
    ARTISTS("artists", "艺人", "♙", true),
    ALBUMS("albums", "专辑", "▣", true),
    GENRES("genres", "流派/风格", "♬", true),
    YEARS("years", "年代", "◷", true),
    TAGS("tags", "标签", "#", true),
    FAVORITES("favorites", "收藏", "☆", false);

    companion object {
        fun fromPathSegment(value: String?): LibraryCategory? =
            entries.firstOrNull { it.pathSegment == value }
    }
}

/** Encoded Navigation routes for Library pages. Opaque IDs are always one URL path segment. */
object LibraryRoutes {
    const val ROOT = "library"
    const val CATEGORY_PATTERN = "library/{kind}"
    const val DETAIL_PATTERN = "library/{kind}/{id}"
    const val KIND_ARGUMENT = "kind"
    const val ID_ARGUMENT = "id"

    fun category(category: LibraryCategory): String = "$ROOT/${category.pathSegment}"

    fun category(kind: String): String {
        val category = LibraryCategory.fromPathSegment(kind)
            ?: throw IllegalArgumentException("Unsupported Library category")
        return category(category)
    }

    fun detail(category: LibraryCategory, sourceId: String): String {
        require(category.hasDetail) { "This Library category does not have an entity detail route" }
        require(sourceId.isNotBlank() && sourceId != "." && sourceId != ".." &&
            '/' !in sourceId && '\\' !in sourceId
        ) { "Invalid Library entity ID" }
        return "$ROOT/${category.pathSegment}/${encodePathSegment(sourceId)}"
    }

    fun detail(kind: String, sourceId: String): String {
        val category = LibraryCategory.fromPathSegment(kind)
            ?: throw IllegalArgumentException("Unsupported Library category")
        return detail(category, sourceId)
    }

    fun categoryOrNull(kind: String?): LibraryCategory? =
        LibraryCategory.fromPathSegment(kind)

    fun decodePathSegment(encodedSegment: String): String =
        URLDecoder.decode(encodedSegment.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun encodePathSegment(value: String): String =
        "http://localhost/".toHttpUrl().newBuilder()
            .addPathSegment(value)
            .build()
            .encodedPath
            .removePrefix("/")
}
