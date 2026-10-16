package phoenixclient.engine

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import phoenixclient.*
import kotlin.concurrent.Volatile

internal class PlatformHttpClient(
    val client: HttpClient,
    // HTTP status of the last failed request, for engines whose exception doesn't carry it (Darwin).
    val lastFailedStatus: () -> Int? = { null },
)

// HttpClient with the WebSockets plugin installed, on the platform's engine (OkHttp on the JVM, Darwin on iOS).
internal expect fun createPlatformHttpClient(untrustedCertificate: Boolean): PlatformHttpClient

/**
 * WebSocket engine running on Ktor. A custom [httpClient] must have the WebSockets plugin installed;
 * when it is null, a client for the platform is created on the first connection.
 */
class KtorEngine(
    private val httpClient: HttpClient? = null,
    val serializer: (OutgoingMessage) -> String = OutgoingMessage::toJson,
    val deserializer: (String) -> IncomingMessage = ::fromJson,
) : WebSocketEngine {
    private val logger = Logger.withTag("PhoenixClient.KtorEngine")

    // Reads incoming frames independently of the connect() caller, like the OkHttp listener thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var defaultClient: PlatformHttpClient? = null

    // Frames are queued and written by a single coroutine, so that send() returns right away like OkHttp's.
    // Awaiting the session's send instead delays the caller until the frame is acknowledged by the platform,
    // which on Darwin lags far behind the network under a burst of messages.
    private class Connection(
        val session: DefaultClientWebSocketSession,
        val outgoing: Channel<String> = Channel(Channel.UNLIMITED),
    )

    @Volatile
    private var connection: Connection? = null

    override suspend fun connect(
        host: String,
        port: Int,
        path: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        ssl: Boolean,
        untrustedCertificate: Boolean,
        receiver: (event: WebSocketEvent) -> Unit,
    ) {
        val platformClient = httpClient?.let { PlatformHttpClient(it) }
            ?: defaultClient
            ?: createPlatformHttpClient(untrustedCertificate).also { defaultClient = it }

        val newSession = try {
            platformClient.client.webSocketSession {
                url {
                    protocol = if (ssl) URLProtocol.WSS else URLProtocol.WS
                    this.host = host
                    this.port = port
                    encodedPath = listOf(path.trim('/'), "websocket")
                        .filter { it.isNotEmpty() }
                        .joinToString("/", prefix = "/")
                    parameters.append("vsn", VSN)
                    params.forEach { (key, value) -> parameters.append(key, value) }
                }
                headers.forEach { (key, value) -> header(key, value) }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.e(ex) { "Failed to open WebSocket" }
            val message = ex.toFailureMessage(platformClient.lastFailedStatus())
            receiver(WebSocketEvent(state = ConnectionState.DISCONNECTED, message = message))
            return
        }

        val newConnection = Connection(newSession)
        connection = newConnection
        receiver(WebSocketEvent(state = ConnectionState.CONNECTED))

        // Writer: the queue is closed by close() or when the session ends.
        scope.launch {
            try {
                for (text in newConnection.outgoing) {
                    newSession.send(Frame.Text(text))
                }
                // Queued frames are sent before closing.
                newSession.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Closing WebSocket"))
            } catch (ex: CancellationException) {
                // The session ended.
            } catch (ex: Exception) {
                logger.e(ex) { "Failed to write on WebSocket" }
            } finally {
                // Don't wait for the server to acknowledge the close: the reader ends and reports DISCONNECTED.
                newSession.cancel()
            }
        }

        // Reader
        scope.launch {
            var failure: IncomingMessage? = null

            try {
                for (frame in newSession.incoming) {
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

                    receiver(WebSocketEvent(message = message))
                }
            } catch (ex: CancellationException) {
                // Closed by close(), the session is cancelled.
            } catch (ex: Exception) {
                logger.e(ex) { "Got a failure on WebSocket" }
                failure = Failure
            } finally {
                newConnection.outgoing.close()
                if (connection === newConnection) {
                    connection = null
                }
                receiver(WebSocketEvent(state = ConnectionState.DISCONNECTED, message = failure))
            }
        }
    }

    override fun send(message: OutgoingMessage): Result<Unit> =
        try {
            logger.d { "Send message $message to WebSocket" }
            val current = connection ?: throw BadActionException("WebSocket is not connected")
            current.outgoing.trySend(serializer(message)).getOrThrow()
            Result.success(Unit)
        } catch (ex: Exception) {
            logger.e(ex) { "Failed to send message $message to WebSocket" }
            Result.failure(ex)
        }

    override fun close() {
        logger.i { "Closing WebSocket" }
        // The writer sends the queued frames, then the close frame.
        connection?.outgoing?.close()
    }
}

// The server refuses the upgrade with 403 when the socket's connect/3 returns an error (bad token...).
// Other refusals (404, 502...) are failures, so the client keeps reconnecting.
// The status comes from the platform when known (Darwin), else from the exception (OkHttp:
// "Expected HTTP 101 response but was '403 Forbidden'").
private fun Exception.toFailureMessage(status: Int?): IncomingMessage =
    if (
        status == 403
        || (status == null && (message?.contains("403") == true || message?.contains("forbidden", ignoreCase = true) == true))
    ) {
        Forbidden
    } else {
        Failure
    }
