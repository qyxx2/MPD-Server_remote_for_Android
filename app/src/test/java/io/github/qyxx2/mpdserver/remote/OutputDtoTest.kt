package io.github.qyxx2.mpdserver.remote
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputSnapshotDto
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
class OutputDtoTest {
 @Test fun outputStateDoesNotConflateSourceFormatWithObservedOutput() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/output-snapshot.json")!!.readText()
  val dto=JsonConfiguration.json.decodeFromString<OutputSnapshotDto>(json)
  assertEquals(io.github.qyxx2.mpdserver.remote.protocol.dto.OutputStatusDto.ACTIVE,dto.states.single().status); assertEquals(96000,dto.states.single().sampleRate)
  assertFalse(dto.states.single().stale); assertNull(dto.lastRequest)
 }
}
