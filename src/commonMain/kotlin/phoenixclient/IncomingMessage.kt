package phoenixclient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer


interface Payload {
    val json: JsonObject

    fun <T> convertTo(deserializer: DeserializationStrategy<T>, key: String? = null): Result<T>
}

inline fun <reified T> Payload.convertTo(key: String? = null): Result<T> = convertTo(serializer<T>(), key)

class JsonPayload(override val json: JsonObject) : Payload {
    override fun <T> convertTo(deserializer: DeserializationStrategy<T>, key: String?): Result<T> = try {
        val target = if (key == null) json else json[key] ?: throw SerializationException("payload has no key '$key'")
        Result.success(JsonProcessor.decodeFromJsonElement(deserializer, target))
    } catch (ex: Exception) {
        Result.failure(ex)
    }

    override fun toString(): String = json.toString()
}

data class IncomingMessage(
    val topic: String,
    val event: String,
    val payload: Payload? = null,
    val ref: String? = null,
    val joinRef: String? = null,
)

private fun JsonElement.toStringOrNull(name: String): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> throw SerializationException("$name must be a string")
}

fun fromJson(input: String): IncomingMessage {
    val values = JsonProcessor.parseToJsonElement(input) as? JsonArray
        ?: throw SerializationException("json must be an array")

    if (values.size < 5) {
        throw SerializationException("values must have size of 5")
    }

    val payload = when (val value = values[4]) {
        is JsonNull -> null
        is JsonObject -> JsonPayload(value)
        else -> throw SerializationException("payload must be an object")
    }

    return IncomingMessage(
        joinRef = values[0].toStringOrNull("join_ref"),
        ref = values[1].toStringOrNull("ref"),
        topic = values[2].toStringOrNull("topic") ?: throw SerializationException("topic must be a string"),
        event = values[3].toStringOrNull("event") ?: throw SerializationException("event must be a string"),
        payload = payload,
    )
}

val Forbidden = IncomingMessage(topic = "phoenix", event = "forbidden")
val Failure = IncomingMessage(topic = "phoenix", event = "failure")

@Serializable
data class Reason(
    val reason: String
)

fun IncomingMessage.isError(): Boolean = event == "phx_error"
fun IncomingMessage.hasStatus(status: String): Boolean =
    payload?.convertTo<String>("status")?.getOrNull() == status
fun IncomingMessage.hasReason(reason: String): Boolean =
    payload?.convertTo<Reason>("response")?.getOrNull()?.reason == reason

fun Flow<IncomingMessage>.filterTopic(topic: String) = filter { it.topic == topic }
fun Flow<IncomingMessage>.filterEvent(event: String) = filter { it.event == event }
