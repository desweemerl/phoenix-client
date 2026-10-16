package phoenixclient

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import phoenixclient.engine.DEFAULT_UNTRUSTED_CERTIFICATE
import phoenixclient.engine.DEFAULT_WS_HOST
import phoenixclient.engine.DEFAULT_WS_PATH
import phoenixclient.engine.DEFAULT_WS_PORT
import phoenixclient.engine.DEFAULT_WS_SSL
import phoenixclient.engine.KtorEngine

// Client for all platforms. A custom httpClient must have the WebSockets plugin installed;
// by default, OkHttp is used on the JVM and Darwin on iOS.
fun ktorPhoenixClient(
    host: String = DEFAULT_WS_HOST,
    port: Int = DEFAULT_WS_PORT,
    path: String = DEFAULT_WS_PATH,
    ssl: Boolean = DEFAULT_WS_SSL,
    untrustedCertificate: Boolean = DEFAULT_UNTRUSTED_CERTIFICATE,
    retryTimeout: DynamicTimeout = DEFAULT_RETRY,
    heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL,
    heartbeatTimeout: Long = DEFAULT_HEARTBEAT_TIMEOUT,
    defaultTimeout: Long = DEFAULT_TIMEOUT,
    httpClient: HttpClient? = null,
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
        host = host,
        port = port,
        path = path,
        ssl = ssl,
        untrustedCertificate = untrustedCertificate,
        retryTimeout = retryTimeout,
        heartbeatInterval = heartbeatInterval,
        heartbeatTimeout = heartbeatTimeout,
        defaultTimeout = defaultTimeout,
        dispatcher = dispatcher,
        scope = scope,
    )
