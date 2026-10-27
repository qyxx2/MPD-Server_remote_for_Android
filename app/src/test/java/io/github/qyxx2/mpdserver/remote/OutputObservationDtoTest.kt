package io.github.qyxx2.mpdserver.remote
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputObservationDto
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
class OutputObservationDtoTest {
 @Test fun outputObservationPreservesIndependentFreshnessAndErrors() {
  val json=javaClass.classLoader!!.getResource("fixtures/task4/output-observation.json")!!.readText()
  val dto=JsonConfiguration.json.decodeFromString<OutputObservationDto>(json)
  assertEquals("fresh",dto.freshness.name); assertNull(dto.errorCode); assertNull(dto.errorMessage)
 }
}
