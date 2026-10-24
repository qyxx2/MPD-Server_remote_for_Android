package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject

/** Immutable canonical snapshot plus strictly extracted watermarks from the full wire document. */
class FullStateSnapshot private constructor(
    val wire: StateSnapshotDto,
    val queueRevision: Long,
    val librarySyncGeneration: Long,
) {
    val epoch: String get() = wire.epoch
    val sequence: Long get() = wire.sequence
    val libraryRevision: Long get() = wire.revisions.library
    val playlistRevision: Long get() = wire.revisions.playlist
    val explicitStopGeneration: Long get() = wire.explicitStopGeneration

    fun covers(watermark: SnapshotWatermark): Boolean =
        epoch == watermark.epoch &&
            sequence >= watermark.sequence &&
            libraryRevision >= watermark.revisions.library &&
            playlistRevision >= watermark.revisions.playlist

    companion object {
        fun from(dto: StateSnapshotDto): FullStateSnapshot {
            require(dto.epoch.isNotBlank()) { "Snapshot epoch must not be blank" }
            require(dto.sequence >= 0) { "Snapshot sequence must not be negative" }
            require(dto.explicitStopGeneration >= 0) { "explicit_stop_generation must not be negative" }
            require(dto.revisions.library >= 0 && dto.revisions.playlist >= 0) { "Snapshot revisions must not be negative" }
            val queueRevision = dto.queue.jsonObject.requiredLong("revision", "queue")
            val libraryGeneration = dto.librarySync.jsonObject.requiredLong("generation", "library_sync")
            require(queueRevision >= 0 && libraryGeneration >= 0) { "Snapshot generations/revisions must not be negative" }
            return FullStateSnapshot(dto, queueRevision, libraryGeneration)
        }

        private fun JsonObject.requiredLong(field: String, owner: String): Long {
            val value = this[field] as? JsonPrimitive
                ?: throw IllegalArgumentException("$owner.$field is required")
            return value.longOrNull ?: throw IllegalArgumentException("$owner.$field must be an integer")
        }
    }
}
