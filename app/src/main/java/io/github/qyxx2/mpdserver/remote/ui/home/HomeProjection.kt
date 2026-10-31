package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryEventDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.decodeResources
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot

/** Pure projections of server-ordered resources; these functions never sort or mutate inputs. */
object HomeProjection {
    fun recentlyUpdated(songs: List<SongDto>): HomeRecentUpdates {
        val groups = LinkedHashMap<String, MutableList<SongDto>>()
        songs.forEach { song ->
            val albumId = song.albumId?.takeIf(String::isNotBlank)
            val key = if (albumId != null) {
                "album:$albumId"
            } else {
                song.songId?.takeIf(String::isNotBlank)?.let { "song:$it" } ?: "file:${song.fileUri}"
            }
            groups.getOrPut(key) { mutableListOf() } += song
        }

        return HomeRecentUpdates(
            cards = groups.map { (key, members) ->
                val representative = members.first()
                val albumId = representative.albumId?.takeIf(String::isNotBlank)
                val artworkSource = members.firstOrNull(::hasAvailableArtwork)
                HomeArtworkCard(
                    key = key,
                    kind = if (albumId == null) HomeCardKind.SINGLE_SONG else HomeCardKind.ALBUM,
                    song = representative,
                    songId = representative.songId,
                    albumId = albumId,
                    artworkSongId = artworkSource?.songId,
                    artwork = artworkSource?.artwork,
                    isPlayable = if (albumId == null) {
                        !representative.songId.isNullOrBlank() &&
                            representative.availabilityStatus == AvailabilityStatus.AVAILABLE
                    } else {
                        members.any {
                            !it.songId.isNullOrBlank() && it.availabilityStatus == AvailabilityStatus.AVAILABLE
                        }
                    },
                )
            },
        )
    }

    fun playbackHistory(
        history: List<HistoryEventDto>,
        librarySongs: List<SongDto>,
    ): HomePlaybackHistory {
        val songsById = librarySongs.mapNotNull { song ->
            song.songId?.takeIf(String::isNotBlank)?.let { it to song }
        }.toMap()
        val libraryOrder = librarySongs
        val groups = LinkedHashMap<String, MutableList<HistoryEventDto>>()
        val fallbackEventOccurrences = mutableMapOf<String, Int>()
        history.forEach { event ->
            val albumId = songsById[event.songId]?.albumId?.takeIf(String::isNotBlank)
            val eventIdentity = listOf(
                event.songId,
                event.startedAt,
                event.endedAt.orEmpty(),
                event.sessionId.orEmpty(),
                event.reason.orEmpty(),
            ).joinToString(separator = "") { "${it.length}:$it" }
            val key = when {
                albumId != null -> "album:$albumId"
                event.historyId != null -> "history:${event.historyId}"
                else -> {
                    val ordinal = fallbackEventOccurrences.getOrDefault(eventIdentity, 0)
                    fallbackEventOccurrences[eventIdentity] = ordinal + 1
                    "history:event:$eventIdentity:$ordinal"
                }
            }
            groups.getOrPut(key) { mutableListOf() } += event
        }

        val cards = groups.map { (key, events) ->
            val newestEvent = events.first()
            val representative = songsById[newestEvent.songId]
            val albumId = representative?.albumId?.takeIf(String::isNotBlank)
            val artworkSource = if (albumId != null) {
                libraryOrder.firstOrNull { it.albumId == albumId && hasAvailableArtwork(it) }
            } else {
                representative?.takeIf(::hasAvailableArtwork)
            }
            HomeArtworkCard(
                key = key,
                kind = when {
                    albumId != null -> HomeCardKind.ALBUM
                    representative == null -> HomeCardKind.HISTORY_ORPHAN
                    else -> HomeCardKind.SINGLE_SONG
                },
                song = representative,
                songId = newestEvent.songId,
                albumId = albumId,
                artworkSongId = artworkSource?.songId,
                artwork = artworkSource?.artwork,
                isPlayable = if (albumId != null) {
                    libraryOrder.any {
                        it.albumId == albumId &&
                            !it.songId.isNullOrBlank() &&
                            it.availabilityStatus == AvailabilityStatus.AVAILABLE
                    }
                } else {
                    !representative?.songId.isNullOrBlank() &&
                        representative?.availabilityStatus == AvailabilityStatus.AVAILABLE
                },
                historyId = newestEvent.historyId,
            )
        }
        val uniqueSongIds = LinkedHashSet<String>()
        history.forEach { event ->
            event.songId.takeIf(String::isNotBlank)?.let(uniqueSongIds::add)
        }
        return HomePlaybackHistory(cards = cards, songIds = uniqueSongIds.toList())
    }

    fun autoplay(
        snapshot: FullStateSnapshot,
        librarySongs: List<SongDto>,
    ): HomeAutoplay {
        val resources = snapshot.wire.decodeResources()
        val playback = resources.playback
        val contextId = playback.playbackContextId
        if (!playback.autoplayEnabled || contextId.isNullOrBlank()) return HomeAutoplay(emptyList())

        val songsById = librarySongs.mapNotNull { song ->
            song.songId?.takeIf(String::isNotBlank)?.let { it to song }
        }.toMap()
        val cards = resources.queue.items
            .asSequence()
            .filter { item ->
                item.position > 0 &&
                    item.source == QueueSourceDto.AUTOPLAY &&
                    item.playbackContextId == contextId
            }
            .sortedBy { it.position }
            .map { item ->
                val song = songsById[item.songId]
                val available = song?.availabilityStatus == AvailabilityStatus.AVAILABLE
                val artwork = song?.takeIf { available }?.artwork
                HomeArtworkCard(
                    key = "queue:${item.queueItemId}",
                    kind = HomeCardKind.AUTOPLAY_OCCURRENCE,
                    song = song,
                    songId = item.songId,
                    albumId = song?.albumId?.takeIf(String::isNotBlank),
                    artworkSongId = if (artwork != null) song?.songId else null,
                    artwork = artwork,
                    isPlayable = available,
                    queueItemId = item.queueItemId,
                )
            }
            .toList()
        return HomeAutoplay(cards)
    }

    private fun hasAvailableArtwork(song: SongDto): Boolean =
        !song.songId.isNullOrBlank() &&
            song.availabilityStatus == AvailabilityStatus.AVAILABLE &&
            !song.artwork?.artworkId.isNullOrBlank()
}
