package phoenixclient

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import phoenixclient.engine.DEFAULT_WS_PATH
import phoenixclient.engine.KtorEngine

// Client for all platforms, connecting to [httpClient]'s server on [httpClient] (see phoenixHttpClient), which the
// application can share for its HTTP requests to the same server.
fun ktorPhoenixClient(
    httpClient: PlatformHttpClient,
    path: String = DEFAULT_WS_PATH,
    retryTimeout: DynamicTimeout = DEFAULT_RETRY,
    heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL,
    heartbeatTimeout: Long = DEFAULT_HEARTBEAT_TIMEOUT,
    defaultTimeout: Long = DEFAULT_TIMEOUT,
    serializer: (message: OutgoingMessage) -> String = { it.toJson() },
    deserializer: (input: String) -> IncomingMessage = ::fromJson,
    dispatcher: CoroutineDispatcher = ioDispatcher,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Result<Client> =
    phoenixClient(
        webSocketEngine = KtorEngine(
            httpClient = httpClient,
            serializer = serializer,
            deserializer = deserializer,
        ),
        host = httpClient.server.host,
        port = httpClient.server.port,
        path = path,
        ssl = httpClient.server.ssl,
        untrustedCertificate = httpClient.server.untrustedCertificate,
        retryTimeout = retryTimeout,
        heartbeatInterval = heartbeatInterval,
        heartbeatTimeout = heartbeatTimeout,
        defaultTimeout = defaultTimeout,
        dispatcher = dispatcher,
        scope = scope,
    )
