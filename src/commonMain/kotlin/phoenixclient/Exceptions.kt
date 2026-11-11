package phoenixclient

class ConfigurationException(message: String) : Exception(message)

class ResponseException(message: String, val source: IncomingMessage) : Exception(message)

class BadActionException(message: String) : Exception(message)

class ChannelException(message: String) : Exception(message)

class TimeoutException(message: String) : Exception(message)

class HttpRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The WebSocket could not be opened. [status] is the HTTP status with which the server refused the handshake,
 * when known: 403 when the socket's connect/3 returns an error (bad token...).
 */
class WebSocketConnectException(message: String, val status: Int? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    val isForbidden: Boolean get() = status == 403
}
