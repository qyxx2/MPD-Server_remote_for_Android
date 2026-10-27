package io.github.qyxx2.mpdserver.remote
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackObservationDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
class PlaybackObservationDtoTest {
 @Test fun keepsControlTargetBoundOccurrenceAndFreshnessAsServerFacts() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/playback-observation.json")!!.readText()
  val dto=JsonConfiguration.json.decodeFromString<PlaybackObservationDto>(json)
  assertEquals("occurrence-1",dto.controlTarget?.queueItemId); assertEquals("opaque-token",dto.controlTarget?.token)
  assertEquals("occurrence-1",dto.boundQueueItemId); assertEquals(io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackSyncStatusDto.CONFIRMED,dto.syncStatus); assertTrue(dto.matchesCurrent!!)
 }
 @Test fun unknownSyncStatusIsRejected() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/playback-observation.json")!!.readText().replace("CONFIRMED", "MAYBE")
  org.junit.Assert.assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<PlaybackObservationDto>(json) }
 }
}
