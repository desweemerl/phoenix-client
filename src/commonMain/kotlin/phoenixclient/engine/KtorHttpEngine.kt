package phoenixclient.engine

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.encodedPath
import io.ktor.util.toMap
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import phoenixclient.ConnectionState
import phoenixclient.Failure
import phoenixclient.HttpRequestException
import phoenixclient.IncomingMessage
import phoenixclient.OutgoingMessage
import phoenixclient.VSN
import phoenixclient.WebSocketConnectException
import phoenixclient.fromJson
import phoenixclient.toJson


// Ktor client with the WebSockets plugin, and the HTTP status of the last failed request for engines whose
// exception doesn't carry it (Darwin).
internal class KtorClient(
    val client: HttpClient,
    val lastFailedStatus: () -> Int? = { null },
)

// OkHttp on the JVM, Darwin on iOS.
internal expect fun createPlatformHttpClient(untrustedCertificate: Boolean): KtorClient

// Frames are queued and written by a single coroutine, so that send() returns right away.
// Awaiting the session's send instead delays the caller until the frame is acknowledged by the platform,
// which on Darwin lags far behind the network under a burst of messages.
private class KtorWebSocketConnection(
    val session: DefaultClientWebSocketSession,
    private val serializer: (OutgoingMessage) -> String,
    private val logger: Logger,
) : PhoenixWebSocketConnection {
    val outgoing: Channel<String> = Channel(Channel.UNLIMITED)

    override fun send(message: OutgoingMessage): Result<Unit> =
        try {
            logger.d { "Send message $message to WebSocket" }
            // Throws once the queue is closed, by close() or when the session ended.
            outgoing.trySend(serializer(message)).getOrThrow()
            Result.success(Unit)
        } catch (ex: Exception) {
            logger.e(ex) { "Failed to send message $message to WebSocket" }
            Result.failure(ex)
        }

    override fun close() {
        logger.i { "Closing WebSocket" }
        // The writer sends the queued frames, then the close frame.
        outgoing.close()
    }
}

/**
 * [PhoenixHttpEngine] running on Ktor, with the HTTP client of the platform (OkHttp on the JVM, Darwin on iOS):
 * the WebSockets and the HTTP requests share its connections and its certificate trust.
 * [close] it once neither a client nor the application use it.
 */
class KtorHttpEngine internal constructor(
    override val server: PhoenixHttpServer,
    private val ktor: KtorClient,
    private val serializer: (OutgoingMessage) -> String = { it.toJson() },
    private val deserializer: (String) -> IncomingMessage = ::fromJson,
) : PhoenixHttpEngine {

    constructor(
        server: PhoenixHttpServer,
        serializer: (message: OutgoingMessage) -> String = { it.toJson() },
        deserializer: (input: String) -> IncomingMessage = ::fromJson,
    ) : this(server, createPlatformHttpClient(server.untrustedCertificate), serializer, deserializer)

    private val logger = Logger.withTag("PhoenixClient.KtorHttpEngine")

    // Reads and writes the frames of every connection, independently of the connectWebSocket() callers.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun request(request: PhoenixHttpRequest): PhoenixHttpResponse {
        val description = "${request.method} ${request.path}"

        return try {
            withTimeout(request.timeout) {
                val response = ktor.client.request {
                    method = HttpMethod.parse(request.method.name)
                    url {
                        protocol = if (server.ssl) URLProtocol.HTTPS else URLProtocol.HTTP
                        host = server.host
                        port = server.port
                        encodedPath = request.path
                        request.query.forEach { (key, value) -> parameters.append(key, value) }
                    }
                    request.headers.forEach { (key, value) -> header(key, value) }
                    request.body?.let { setBody(ByteArrayContent(it.content, ContentType.parse(it.contentType))) }
                }

                PhoenixHttpResponse(response.status.value, response.headers.toMap(), response.bodyAsBytes())
            }
        } catch (ex: TimeoutCancellationException) {
            // Only our own timeout is a failed request: rethrow if the caller's coroutine was cancelled.
            currentCoroutineContext().ensureActive()
            throw HttpRequestException("$description timed out after ${request.timeout} ms", ex)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw HttpRequestException("$description failed: ${ex.message}", ex)
        }
    }

    override suspend fun connectWebSocket(
        path: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        receiver: (event: PhoenixWebSocketEvent) -> Unit,
    ): Result<PhoenixWebSocketConnection> {
        val socketPath = listOf(path.trim('/'), "websocket")
            .filter { it.isNotEmpty() }
            .joinToString("/", prefix = "/")

        val session = try {
            ktor.client.webSocketSession {
                url {
                    protocol = if (server.ssl) URLProtocol.WSS else URLProtocol.WS
                    host = server.host
                    port = server.port
                    encodedPath = socketPath
                    parameters.append("vsn", VSN)
                    params.forEach { (key, value) -> parameters.append(key, value) }
                }
                headers.forEach { (key, value) -> header(key, value) }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.e(ex) { "Failed to open WebSocket" }
            return Result.failure(
                WebSocketConnectException(
                    "Failed to open WebSocket on $socketPath: ${ex.message}",
                    ex.refusedStatus(ktor.lastFailedStatus()),
                    ex,
                )
            )
        }

        val connection = KtorWebSocketConnection(session, serializer, logger)

        // Writer: the queue is closed by close() or when the session ends.
        scope.launch {
            try {
                for (text in connection.outgoing) {
                    session.send(Frame.Text(text))
                }
                // Queued frames are sent before closing.
                session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Closing WebSocket"))
            } catch (ex: CancellationException) {
                // The session ended, or the engine was closed.
            } catch (ex: Exception) {
                logger.e(ex) { "Failed to write on WebSocket" }
            } finally {
                // Don't wait for the server to acknowledge the close: the reader ends and reports DISCONNECTED.
                session.cancel()
            }
        }

        // Reader
        scope.launch {
            var failure: IncomingMessage? = null

            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) {
                        continue
                    }

                    val text = frame.readText()
                    val message = try {
                        deserializer(text)
                    } catch (ex: Exception) {
                        logger.e(ex) { "Failed to deserialize message: $text" }
                        continue
                    }

                    receiver(PhoenixWebSocketEvent(message = message))
                }
            } catch (ex: CancellationException) {
                // Closed by close(): the session is cancelled.
            } catch (ex: Exception) {
                logger.e(ex) { "Got a failure on WebSocket" }
                failure = Failure
            } finally {
                connection.outgoing.close()
                session.cancel()
                receiver(PhoenixWebSocketEvent(state = ConnectionState.DISCONNECTED, message = failure))
            }
        }

        return Result.success(connection)
    }

    override fun close() {
        logger.i { "Closing engine" }
        // Ends the readers and writers of every connection: each reader reports DISCONNECTED.
        scope.cancel()
        ktor.client.close()
    }
}

// HTTP status with which the server refused the handshake, when known: 403 when the socket's connect/3 returns
// an error (bad token...). It comes from the platform when it records it (Darwin), else from the exception
// (OkHttp: "Expected HTTP 101 response but was '403 Forbidden'").
private fun Exception.refusedStatus(recorded: Int?): Int? =
    recorded ?: if (message?.contains("403") == true || message?.contains("forbidden", ignoreCase = true) == true) {
        403
    } else {
        null
    }
