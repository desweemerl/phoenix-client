package phoenixclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Serializable
data class PartialPayload(
    val name: String,
    val nickname: String? = null,
)

class IncomingMessageTest {

    @Test
    fun testMessageDeserialization() {
        val message = fromJson("""["1","2","test","myEvent",{"status":"ok","response":{"name":"hello"}}]""")

        Assertions.assertEquals("1", message.joinRef)
        Assertions.assertEquals("2", message.ref)
        Assertions.assertEquals("test", message.topic)
        Assertions.assertEquals("myEvent", message.event)
        Assertions.assertTrue(message.hasStatus("ok"))
        Assertions.assertEquals(PartialPayload("hello"), message.payload?.convertTo<PartialPayload>("response")?.getOrThrow())
    }

    @Test
    fun testMessageDeserializationWithNulls() {
        val message = fromJson("""[null,null,"phoenix","heartbeat",null]""")

        Assertions.assertNull(message.joinRef)
        Assertions.assertNull(message.ref)
        Assertions.assertNull(message.payload)
    }

    @Test
    fun testMalformedMessage() {
        assertThrows<SerializationException> { fromJson("""{"topic":"test"}""") }
        assertThrows<SerializationException> { fromJson("""[null,null,"test","myEvent"]""") }
        assertThrows<SerializationException> { fromJson("""[null,null,"test","myEvent",[]]""") }
    }

    @Test
    fun testPayloadIgnoresUnknownKeys() {
        val payload = fromJson("""[null,null,"test","myEvent",{"name":"hello","age":42}]""").payload!!

        Assertions.assertEquals(PartialPayload("hello"), payload.convertTo<PartialPayload>().getOrThrow())
    }

    @Test
    fun testPayloadMissingKey() {
        val payload = fromJson("""[null,null,"test","myEvent",{"name":"hello"}]""").payload!!

        Assertions.assertTrue(payload.convertTo<PartialPayload>("response").exceptionOrNull() is SerializationException)
    }

    @Test
    fun testPayloadRoundTrip() {
        val message = OutgoingMessage(ref = "1", topic = "test", event = "myEvent", payload = payloadOf(PartialPayload("hello")))

        // Null properties are not sent.
        Assertions.assertEquals("""[null,"1","test","myEvent",{"name":"hello"}]""", message.toJson())
    }
}
