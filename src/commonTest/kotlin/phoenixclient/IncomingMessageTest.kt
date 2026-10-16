package phoenixclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.test.*

@Serializable
data class PartialPayload(
    val name: String,
    val nickname: String? = null,
)

class IncomingMessageTest {

    @Test
    fun testMessageDeserialization() {
        val message = fromJson("""["1","2","test","myEvent",{"status":"ok","response":{"name":"hello"}}]""")

        assertEquals("1", message.joinRef)
        assertEquals("2", message.ref)
        assertEquals("test", message.topic)
        assertEquals("myEvent", message.event)
        assertTrue(message.hasStatus("ok"))
        assertEquals(PartialPayload("hello"), message.payload?.convertTo<PartialPayload>("response")?.getOrThrow())
    }

    @Test
    fun testMessageDeserializationWithNulls() {
        val message = fromJson("""[null,null,"phoenix","heartbeat",null]""")

        assertNull(message.joinRef)
        assertNull(message.ref)
        assertNull(message.payload)
    }

    @Test
    fun testMalformedMessage() {
        assertFailsWith<SerializationException> { fromJson("""{"topic":"test"}""") }
        assertFailsWith<SerializationException> { fromJson("""[null,null,"test","myEvent"]""") }
        assertFailsWith<SerializationException> { fromJson("""[null,null,"test","myEvent",[]]""") }
    }

    @Test
    fun testPayloadIgnoresUnknownKeys() {
        val payload = fromJson("""[null,null,"test","myEvent",{"name":"hello","age":42}]""").payload!!

        assertEquals(PartialPayload("hello"), payload.convertTo<PartialPayload>().getOrThrow())
    }

    @Test
    fun testPayloadMissingKey() {
        val payload = fromJson("""[null,null,"test","myEvent",{"name":"hello"}]""").payload!!

        assertTrue(payload.convertTo<PartialPayload>("response").exceptionOrNull() is SerializationException)
    }

    @Test
    fun testPayloadRoundTrip() {
        val message = OutgoingMessage(ref = "1", topic = "test", event = "myEvent", payload = payloadOf(PartialPayload("hello")))

        // Null properties are not sent.
        assertEquals("""[null,"1","test","myEvent",{"name":"hello"}]""", message.toJson())
    }
}
