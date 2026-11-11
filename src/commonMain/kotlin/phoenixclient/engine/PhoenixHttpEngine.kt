package phoenixclient.engine

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import phoenixclient.ConnectionState
import phoenixclient.DEFAULT_TIMEOUT
import phoenixclient.HttpRequestException
import phoenixclient.IncomingMessage
import phoenixclient.OutgoingMessage
import phoenixclient.WebSocketConnectException


const val DEFAULT_PORT = 443
const val DEFAULT_SSL = true
const val DEFAULT_UNTRUSTED_CERTIFICATE = false
const val DEFAULT_SOCKET_PATH = "/socket"

/**
 * Server of a [PhoenixHttpEngine], shared by its WebSockets and its HTTP requests.
 * [untrustedCertificate] accepts any server certificate: development only.
 */
data class PhoenixHttpServer(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val ssl: Boolean = DEFAULT_SSL,
    val untrustedCertificate: Boolean = DEFAULT_UNTRUSTED_CERTIFICATE,
)

enum class PhoenixHttpMethod { GET, HEAD, POST, PUT, PATCH, DELETE }

class PhoenixHttpBody(
    val content: ByteArray,
    val contentType: String,
) {
    companion object {
        fun text(text: String, contentType: String = "text/plain; charset=UTF-8") =
            PhoenixHttpBody(text.encodeToByteArray(), contentType)

        fun <T> json(value: T, serializer: SerializationStrategy<T>, json: Json = Json): PhoenixHttpBody =
            text(json.encodeToString(serializer, value), "application/json")
    }
}

/**
 * Request to the server of the engine.
 *
 * [path] is absolute ("/api/v1/items") and already percent-encoded: it is sent as is, so a segment with a space or
 * a non-ASCII character must be given encoded ("/items/a%20b").
 * [query] parameters are given raw, the engine encodes their names and values. A name can be repeated, as Phoenix
 * expects for a list: `"ids[]" to "1", "ids[]" to "2"`.
 * [timeout] (ms) covers the whole request, until the body of the response is read.
 */
data class PhoenixHttpRequest(
    val method: PhoenixHttpMethod,
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val body: PhoenixHttpBody? = null,
    val timeout: Long = DEFAULT_TIMEOUT,
)

/** Response of any status: only a failure to get it (network, TLS, timeout...) throws [HttpRequestException]. */
class PhoenixHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    val isSuccess: Boolean get() = status in 200..299

    val text: String get() = body.decodeToString()

    /** First value of the header [name], ignoring its case. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** Throws a SerializationException when the body is not the expected JSON. */
    fun <T> json(deserializer: DeserializationStrategy<T>, json: Json = lenientJson): T =
        json.decodeFromString(deserializer, text)

    override fun toString() = "PhoenixHttpResponse(status=$status)"
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Event of a WebSocket connection: an incoming [message], and/or the end of the connection
 * ([state] = [ConnectionState.DISCONNECTED], with the [phoenixclient.Failure] message when it ended abnormally).
 */
class PhoenixWebSocketEvent(
    val state: ConnectionState? = null,
    val message: IncomingMessage? = null,
)

/** A WebSocket connection opened by [PhoenixHttpEngine.connectWebSocket]. */
interface PhoenixWebSocketConnection {
    /** Queues [message] without waiting for it to be written. Fails once the connection is closed. */
    fun send(message: OutgoingMessage): Result<Unit>

    /**
     * Closes this connection only: the messages already sent are written first, then the receiver gets
     * [ConnectionState.DISCONNECTED]. Does nothing when the connection is already closed.
     */
    fun close()
}

/**
 * HTTP requests and WebSockets to a Phoenix server ([server]), without exposing the library running them.
 * Implemented by [KtorHttpEngine]; applications can implement it to fake the server in tests.
 */
interface PhoenixHttpEngine {
    val server: PhoenixHttpServer

    /** Throws [HttpRequestException] when no response is received. */
    suspend fun request(request: PhoenixHttpRequest): PhoenixHttpResponse

    /**
     * Opens a WebSocket on [path] ("/socket": "/websocket" is appended) and returns it once connected, or a
     * failure when it could not be opened: a [WebSocketConnectException] carrying the HTTP status when the server
     * refused the handshake.
     *
     * An engine can hold several connections at the same time, each one with its own [receiver], which gets:
     * - nothing when the connection failed to open;
     * - else the incoming messages, then [ConnectionState.DISCONNECTED] exactly once, however the connection
     *   ended (closed by [PhoenixWebSocketConnection.close], by [close], by the server or by a failure).
     *
     * [receiver] is called from the threads of the engine, possibly before this function returns: it must not block.
     */
    suspend fun connectWebSocket(
        path: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        receiver: (event: PhoenixWebSocketEvent) -> Unit,
    ): Result<PhoenixWebSocketConnection>

    /**
     * Closes every open WebSocket connection (their receivers get [ConnectionState.DISCONNECTED]) and releases
     * the resources of the engine, which can't be used afterwards. Called by the owner of the engine: a
     * [phoenixclient.Client] only closes its own connection.
     */
    fun close()
}

suspend fun PhoenixHttpEngine.get(
    path: String,
    query: List<Pair<String, String>> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    timeout: Long = DEFAULT_TIMEOUT,
) = request(PhoenixHttpRequest(PhoenixHttpMethod.GET, path, query, headers, timeout = timeout))

suspend fun PhoenixHttpEngine.post(
    path: String,
    body: PhoenixHttpBody? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = DEFAULT_TIMEOUT,
) = request(PhoenixHttpRequest(PhoenixHttpMethod.POST, path, headers = headers, body = body, timeout = timeout))

suspend fun PhoenixHttpEngine.put(
    path: String,
    body: PhoenixHttpBody? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = DEFAULT_TIMEOUT,
) = request(PhoenixHttpRequest(PhoenixHttpMethod.PUT, path, headers = headers, body = body, timeout = timeout))

suspend fun PhoenixHttpEngine.patch(
    path: String,
    body: PhoenixHttpBody? = null,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = DEFAULT_TIMEOUT,
) = request(PhoenixHttpRequest(PhoenixHttpMethod.PATCH, path, headers = headers, body = body, timeout = timeout))

suspend fun PhoenixHttpEngine.delete(
    path: String,
    headers: Map<String, String> = emptyMap(),
    timeout: Long = DEFAULT_TIMEOUT,
) = request(PhoenixHttpRequest(PhoenixHttpMethod.DELETE, path, headers = headers, timeout = timeout))
