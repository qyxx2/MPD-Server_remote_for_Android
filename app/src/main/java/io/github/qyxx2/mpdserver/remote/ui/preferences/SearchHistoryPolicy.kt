package io.github.qyxx2.mpdserver.remote.ui.preferences

/** Pure policy shared by persistent search history storage and unit tests. */
internal object SearchHistoryPolicy {
    const val MAX_ENTRIES = 10

    fun normalize(history: List<String>): List<String> = buildList {
        history.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_ENTRIES)
            .forEach(::add)
    }

    fun record(history: List<String>, query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return normalize(history)
        return buildList {
            add(normalized)
            normalize(history)
                .asSequence()
                .filterNot { it == normalized }
                .take(MAX_ENTRIES - 1)
                .forEach(::add)
        }
    }
}
