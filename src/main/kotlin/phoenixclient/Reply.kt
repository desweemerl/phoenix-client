package phoenixclient

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer


enum class Status {
    OK,
    ERROR,
    UNKNOWN,
}

interface Reply {
    val status: Status

    fun <T> convertTo(deserializer: DeserializationStrategy<T>): Result<T>
}

inline fun <reified T> Reply.convertTo(): Result<T> = convertTo(serializer<T>())

fun String.toStatus() = when (this) {
    "ok" -> Status.OK
    "error" -> Status.ERROR
    else -> Status.UNKNOWN
}

class ReplyDeserializer(
    private val payload: Payload,
) : Reply {
    override val status: Status
        get() = payload.convertTo<String>("status").getOrThrow().toStatus()

    override fun <T> convertTo(deserializer: DeserializationStrategy<T>): Result<T> =
        payload.convertTo(deserializer, "response")
}

fun IncomingMessage.toReply(): Result<Reply> =
    if (payload == null) {
        Result.failure(Exception("incoming message has no payload"))
    } else if (event != "phx_reply") {
        Result.failure(Exception("incoming message is not a reply"))
    } else {
        Result.success(ReplyDeserializer(payload))
    }

@Serializable
data class ReplyReason(
    val reason: String,
)

fun Reply.isError(reason: String? = null): Boolean =
    status == Status.ERROR
            && (reason == null || convertTo<ReplyReason>().getOrNull()?.reason == reason)

fun Reply.isTopicClosed(): Boolean = isError("unmatched topic")
