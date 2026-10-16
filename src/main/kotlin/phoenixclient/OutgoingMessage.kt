package phoenixclient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

val emptyPayload = JsonObject(emptyMap())

data class OutgoingMessage(
    val topic: String,
    val event: String,
    val payload: JsonElement = emptyPayload,
    val ref: String,
    val joinRef: String? = null,
)

// Converts a @Serializable value (or a Map, List, primitive...) to a payload.
inline fun <reified T> payloadOf(value: T): JsonElement = JsonProcessor.encodeToJsonElement(value)

fun OutgoingMessage.toJson(): String =
    JsonArray(
        listOf(
            JsonPrimitive(joinRef),
            JsonPrimitive(ref),
            JsonPrimitive(topic),
            JsonPrimitive(event),
            payload,
        )
    ).toString()
