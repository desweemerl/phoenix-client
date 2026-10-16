package phoenixclient

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import phoenixclient.engine.*


enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
}

class ClientState(
    val active: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
)

fun Flow<ClientState>.isConnected() = this.filter { it.connectionState == ConnectionState.CONNECTED }.map { true }
suspend fun Flow<ClientState>.waitConnected() = this.isConnected().first()

fun Flow<ClientState>.isConnecting() = this.filter { it.connectionState == ConnectionState.CONNECTING }.map { true }
suspend fun Flow<ClientState>.waitConnecting() = this.isConnecting().first()
fun Flow<ClientState>.isDisconnected() = this.filter { it.connectionState == ConnectionState.DISCONNECTED }.map { true }
suspend fun Flow<ClientState>.waitDisconnected() = this.isDisconnected().first()

fun Flow<ClientState>.isActive() = this.map { it.active }

interface Client {
    val state: Flow<ClientState>
    val messages: Flow<IncomingMessage>
    val active: Boolean

    fun connect(params: Map<String, String> = mapOf(), headers: Map<String, String> = mapOf())
    suspend fun disconnect()

    suspend fun join(topic: String, payload: JsonElement = emptyPayload): Result<Channel>
    suspend fun join(topic: String, payload: JsonElement, timeout: DynamicTimeout): Result<Channel>
}

// Typed payloads: T must be @Serializable (or a Map, List, primitive...).
suspend inline fun <reified T> Client.join(topic: String, payload: T): Result<Channel> =
    join(topic, payloadOf(payload))

suspend inline fun <reified T> Client.join(topic: String, payload: T, noinline timeout: DynamicTimeout): Result<Channel> =
    join(topic, payloadOf(payload), timeout)

fun refGenerator(): () -> Long {
    // Atomic since messages are sent concurrently from several threads.
    val ref = atomic(0L)

    return {
        ref.updateAndGet { if (it == Long.MAX_VALUE) 0 else it + 1 }
    }
}

internal class ClientImpl(
    val host: String = DEFAULT_WS_HOST,
    val port: Int = DEFAULT_WS_PORT,
    val path: String = DEFAULT_WS_PATH,
    val ssl: Boolean = DEFAULT_WS_SSL,
    val untrustedCertificate: Boolean = DEFAULT_UNTRUSTED_CERTIFICATE,
    val retry: DynamicTimeout = DEFAULT_RETRY,
    val heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL,
    val heartbeatTimeout: Long = DEFAULT_HEARTBEAT_TIMEOUT,
    private val defaultTimeout: Long = DEFAULT_TIMEOUT,
    private val webSocketEngine: WebSocketEngine,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : Client {
    private val logger = Logger.withTag("PhoenixClient.Client")

    // Manage message ref
    private val messageRef = refGenerator()

    // Channels storage
    private val channels = mutableMapOf<String, ChannelImpl>()

    private val _state = MutableStateFlow(ClientState())
    override val state = _state.asStateFlow()
    override val active: Boolean
        get() = _state.value.active

    // Incoming messages. Buffers are unbounded so that a slow subscriber never causes messages to be dropped:
    // they are emitted from the WebSocket reader thread, which must not block (a subscriber may be waiting
    // for a reply that this thread has yet to read).
    private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = UNLIMITED)

    // Incoming messages for matching replies to requests. send() subscribes before sending, so no replay is needed.
    private val refMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = UNLIMITED)
    override val messages = _messages.asSharedFlow()

    private var webSocketJob: Job? = null

    init {
        if (heartbeatInterval < defaultTimeout) {
            throw ConfigurationException(
                "heartbeatInterval $heartbeatInterval must be greater or equal to defaultTimeout $defaultTimeout"
            )
        }
    }

    private suspend fun send(
        topic: String,
        event: String,
        payload: JsonElement,
        timeout: DynamicTimeout,
        noReply: Boolean = false,
    ): Result<IncomingMessage?> {
        val channel = channels[topic]
        val isJoinEvent = event == "phx_join"
        val isLeaveEvent = event == "phx_leave"

        // No need to join a channel with the topic "phoenix" (heartbeat).
        if (
            (topic != "phoenix" && channel == null)
            || (channel?.isJoinedOnce == false && !isJoinEvent)
        ) {
            return Result.failure(
                BadActionException(
                    "channel with topic '$topic' was never joined. " +
                            "Join the channel before pushing message"
                )
            )
        }

        var result: Result<IncomingMessage?>? = null
        var attempt = 0

        while (true) {
            val currentTimeout: Long = timeout(attempt)
                ?: return Result.failure(TimeoutException("number of attempt ($attempt) exceeds limit"))

            val ref = messageRef().toString()

            try {
                withTimeout(currentTimeout) {
                    if (channel != null && channel.state.value != ChannelState.JOINED) {
                        if (isJoinEvent) {
                            if (state.value.connectionState != ConnectionState.CONNECTED) {
                                result = Result.failure(
                                    BadActionException("failed to join channel with topic '${channel.topic}' because WebSocket is not connected")
                                )
                                return@withTimeout
                            }
                        } else if (!isLeaveEvent) {
                            logger.d {
                                "Waiting for channel with topic '${channel.topic}' to join before sending " +
                                        "message with event='$event' and payload='$payload'"
                            }

                            channel.state.filter { it == ChannelState.JOINED }.first()
                        }
                    }

                    val joinRef = if (isJoinEvent) ref else channel?.joinRef
                    val outgoingMessage = OutgoingMessage(topic, event, payload, ref, joinRef)

                    if (noReply) {
                        webSocketEngine.send(outgoingMessage)
                        result = Result.success(null)
                        return@withTimeout
                    }

                    // Send once subscribed, so that a reply arriving right away is not missed.
                    val replies = refMessages
                        .onSubscription { webSocketEngine.send(outgoingMessage) }
                        .filter { it.ref == ref }
                        .map { Result.success(it) }

                    val flows = arrayOf(
                        replies,
                        if (topic != "phoenix" && !isJoinEvent && !isLeaveEvent && channel != null) {
                            channel.state.filter { it != ChannelState.JOINED }.map {
                                Result.failure(ChannelException("Channel with topic '$topic' was closed"))
                            }
                        } else {
                            null
                        }
                    )

                    val flow = merge(*flows.filterNotNull().toTypedArray())
                    result = flow.first()

                    result?.getOrNull()?.let {
                        if (it.isError() || it.hasStatus("error")) {
                            result = Result.failure(ResponseException("get an error in request with ref '$ref'", it))
                        }
                    }
                }
            } catch (ex: TimeoutCancellationException) {
                // Only our own timeout is retried: rethrow if the caller's coroutine was cancelled.
                currentCoroutineContext().ensureActive()
                result = Result.failure(TimeoutException("request with ref '$ref' timed out"))
            } catch (ex: Exception) {
                result = Result.failure(ex)
            }

            if (result != null && result?.exceptionOrNull() !is TimeoutException) {
                break
            }

            attempt++
        }

        return result ?: Result.failure(ChannelException("no response found"))
    }

    private fun dirtyCloseChannels() {
        channels.values.forEach { it.dirtyClose() }
    }

    private suspend fun rejoinChannel(topic: String) = coroutineScope {
        channels[topic]?.let {
            launch {
                it.dirtyClose()
                it.rejoin()
            }
        }
    }

    private suspend fun rejoinChannels() {
        channels.values.filter { it.isJoinedOnce }.forEach { it.rejoin() }
    }

    override suspend fun join(topic: String, payload: JsonElement): Result<Channel> =
        join(topic, payload, defaultTimeout.toDynamicTimeout(true))

    override suspend fun join(topic: String, payload: JsonElement, timeout: DynamicTimeout): Result<Channel> {
        val channel = channels.getOrPut(topic) {
            val sendToSocket: suspend (String, JsonElement, DynamicTimeout, Boolean)
            -> Result<IncomingMessage?> =
                { event, payload, channelTimeout, noReply ->
                    send(topic, event, payload, channelTimeout, noReply)
                }

            val disposeFromSocket: suspend (topic: String) -> Unit = {
                channels[topic]?.let {
                    it.close()
                    channels.remove(topic)
                }
            }

            val topicMessages = messages.filter {
                it.topic == topic
            }

            return@getOrPut ChannelImpl(topic, topicMessages, sendToSocket, disposeFromSocket)
        }

        return if (!state.value.active) {
            Result.failure(BadActionException("Socket is not active"))
        } else if (channel.isJoinedOnce) {
            if (channel.state.value != ChannelState.JOINED) {
                logger.d { "WebSocket is connected and channel with topic '$topic' will be joined automatically" }
            }
            Result.success(channel)
        } else if (
            state.value.connectionState == ConnectionState.CONNECTED
            && channel.state.value == ChannelState.CLOSE
        ) {
            logger.d { "Joining channel with topic '$topic'" }
            channel.join(payload)
        } else {
            logger.d { "Waiting WebSocket to be connected before joining channel with topic '$topic'" }

            try {
                dynamicTimer(timeout) {
                    state.filter { it.active && it.connectionState == ConnectionState.CONNECTED }.first()
                }
                channel.join(payload)
            } catch (ex: Exception) {
                Result.failure(BadActionException("WebSocket is not active"))
            }
        }
    }

    private suspend fun launchWebSocket(params: Map<String, String>, headers: Map<String, String>) = coroutineScope {
        if (state.value.connectionState != ConnectionState.DISCONNECTED) {
            return@coroutineScope
        }

        logger.i { "Launching webSocket" }
        _state.update { ClientState(active = it.active, connectionState = ConnectionState.CONNECTING) }

        webSocketEngine.connect(
            host = host,
            port = port,
            path = path,
            params = params,
            headers = headers,
            ssl = ssl,
            untrustedCertificate = untrustedCertificate,
        ) { event ->
            val incomingMessage = event.message

            if (incomingMessage != null) {
                logger.d { "Receiving message from engine: $incomingMessage" }

                if (!_messages.tryEmit(incomingMessage)) {
                    logger.w { "Failed to emit message: $incomingMessage" }
                }

                if (!refMessages.tryEmit(incomingMessage)) {
                    logger.w { "Failed to emit message for internal processing: $incomingMessage" }
                }

                // TODO: Check forbidden on both WebSocket and channel
                if (incomingMessage == Forbidden) {
                    logger.i { "Received message 'forbidden'" }
                    _state.update { ClientState(active = false, connectionState = it.connectionState) }
                } else if (incomingMessage.isError()) {
                    launch {
                        rejoinChannel(incomingMessage.topic)
                    }
                }
            }

            val newState = event.state

            if (newState != null) {
                logger.d { "Receiving new state '$newState' from WebSocket engine" }
                _state.update { ClientState(active = it.active, connectionState = newState) }
            }
        }

        state.waitDisconnected()
    }

    private suspend fun launchHeartbeat() {
        while (state.value.active) {
            delay(heartbeatInterval)

            if (state.value.connectionState == ConnectionState.CONNECTED) {
                val result = send(
                    topic = "phoenix",
                    event = "heartbeat",
                    payload = emptyPayload,
                    timeout = heartbeatTimeout.toDynamicTimeout(),
                )
                if (result.isFailure) {
                    logger.d { "Reconnecting WebSocket because heartbeat gave error: ${result.exceptionOrNull()?.message}" }
                    webSocketEngine.close()
                }
            }
        }
    }

    private suspend fun monitorConnection(params: Map<String, String>, headers: Map<String, String>) {
        dynamicTimer(retry) {
            if (!state.value.active) {
                throw BadActionException("WebSocket is not active")
            }

            // Let the webSocket set the connection up
            if (state.value.connectionState == ConnectionState.DISCONNECTED) {
                webSocketJob?.cancelAndJoin()
                webSocketJob = scope.launch {
                    withContext(dispatcher) {
                        launchWebSocket(params = params, headers = headers)
                    }
                }
            }

            state.waitConnected()
        }
    }

    override fun connect(params: Map<String, String>, headers: Map<String, String>) {
        logger.i { "Connect client" }

        if (state.value.active) {
            throw Exception("WebSocket is already active")
        }

        _state.update { ClientState(active = true, connectionState = it.connectionState) }

        // Retry after being disconnected
        scope.launch {
            withContext(dispatcher) {
                try {
                    monitorConnection(params = params, headers = headers)
                } catch (ex: Exception) {
                    logger.e { "Failed to launch the WebSocket: ${ex.message}" }
                    return@withContext
                }

                var heartbeatJob: Job? = null
                var joinChannelsJob: Job? = null

                state.collect {
                    logger.d {
                        "Client state updated: active='${it.active}' connectionState='${it.connectionState}'"
                    }
                    try {
                        if (!it.active) {
                            webSocketJob?.cancelAndJoin()
                            cancel()
                        } else if (it.connectionState == ConnectionState.CONNECTED) {
                            if (heartbeatJob?.isActive != true) {
                                heartbeatJob = launch {
                                    launchHeartbeat()
                                }
                            }

                            if (joinChannelsJob?.isActive != true) {
                                joinChannelsJob = launch {
                                    rejoinChannels()
                                }
                            }
                        } else if (it.connectionState == ConnectionState.DISCONNECTED) {
                            heartbeatJob?.cancelAndJoin()
                            dirtyCloseChannels()
                            monitorConnection(params = params, headers = headers)
                        }
                    } catch (ex: Exception) {
                        logger.e { "ex: ${ex.message}" }
                    }
                }
            }
        }
    }

    override suspend fun disconnect() {
        logger.i { "Disconnect client" }

        _state.update { ClientState(active = false, connectionState = it.connectionState) }

        // Iterate over a copy: leave() removes the channel from the map.
        coroutineScope {
            channels.values.toList().forEach { launch { it.leave() } }
        }
        channels.clear()

        webSocketEngine.close()
    }
}

fun phoenixClient(
    webSocketEngine: WebSocketEngine,
    host: String = DEFAULT_WS_HOST,
    port: Int = DEFAULT_WS_PORT,
    path: String = DEFAULT_WS_PATH,
    ssl: Boolean = DEFAULT_WS_SSL,
    untrustedCertificate: Boolean = DEFAULT_UNTRUSTED_CERTIFICATE,
    retryTimeout: DynamicTimeout = DEFAULT_RETRY,
    heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL,
    heartbeatTimeout: Long = DEFAULT_HEARTBEAT_TIMEOUT,
    defaultTimeout: Long = DEFAULT_TIMEOUT,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Result<Client> =
    try {
        Result.success(
            ClientImpl(
                host,
                port,
                path,
                ssl,
                untrustedCertificate,
                retryTimeout,
                heartbeatInterval,
                heartbeatTimeout,
                defaultTimeout,
                webSocketEngine,
                dispatcher,
                scope,
            )
        )
    } catch (ex: Exception) {
        Result.failure(ex)
    }