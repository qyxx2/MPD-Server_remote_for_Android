package io.github.qyxx2.mpdserver.remote
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
class QueueDtoTest {
 @Test fun repeatedSongKeepsDistinctQueueOccurrenceIdentity() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/queue-list.json")!!.readText()
  val dto=JsonConfiguration.json.decodeFromString<QueueListDto>(json)
  assertEquals(2,dto.count); assertEquals(dto.items[0].songId,dto.items[1].songId)
  assertNotEquals(dto.items[0].queueItemId,dto.items[1].queueItemId); assertEquals(1,dto.items[1].position)
 }
 @Test fun unknownQueueSourceIsRejectedRatherThanSilentlyAccepted() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/queue-list.json")!!.readText().replace("AUTOPLAY", "UNKNOWN")
  org.junit.Assert.assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<QueueListDto>(json) }
 }
 @Test fun queueSnapshotRetainsRevisionSeparatelyFromApiList() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/queue-snapshot.json")!!.readText()
  val dto=JsonConfiguration.json.decodeFromString<QueueSnapshotDto>(json)
  assertEquals(17,dto.revision); assertEquals("occurrence-1",dto.items.single().queueItemId)
 }
}
