package phoenixclient

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement

enum class ChannelState {
    JOINING,
    JOINED,
    LEAVING,
    CLOSE,
}


interface Channel {
    val topic: String

    val state: Flow<ChannelState>
    val messages: Flow<IncomingMessage>
    val joinRef: String?

    suspend fun pushNoReply(event: String, payload: JsonElement = emptyPayload): Result<Unit>
    suspend fun pushNoReply(event: String, payload: JsonElement, timeout: Long): Result<Unit>

    suspend fun push(event: String, payload: JsonElement = emptyPayload): Result<Reply>
    suspend fun push(event: String, payload: JsonElement, timeout: Long): Result<Reply>

    suspend fun leave()
}

// Typed payloads: T must be @Serializable (or a Map, List, primitive...).
suspend inline fun <reified T> Channel.pushNoReply(event: String, payload: T): Result<Unit> =
    pushNoReply(event, payloadOf(payload))

suspend inline fun <reified T> Channel.pushNoReply(event: String, payload: T, timeout: Long): Result<Unit> =
    pushNoReply(event, payloadOf(payload), timeout)

suspend inline fun <reified T> Channel.push(event: String, payload: T): Result<Reply> =
    push(event, payloadOf(payload))

suspend inline fun <reified T> Channel.push(event: String, payload: T, timeout: Long): Result<Reply> =
    push(event, payloadOf(payload), timeout)

internal class ChannelImpl(
    override val topic: String,
    override val messages: Flow<IncomingMessage>,

    private val sendToSocket: suspend (
        event: String,
        payload: JsonElement,
        timeout: DynamicTimeout,
        noRepy: Boolean,
    )
    -> Result<IncomingMessage?>,
    private val disposeFromSocket: suspend (topic: String) -> Unit,
    private val defaultTimeout: Long = DEFAULT_TIMEOUT,
) : Channel {
    private val logger = Logger.withTag("PhoenixClient.Channel")

    private val _state = MutableStateFlow(ChannelState.CLOSE)
    override val state = _state.asStateFlow()

    private var joinPayload: JsonElement? = null
    private var _joinRef: String? = null
    override val joinRef: String?
        get() = _joinRef

    internal val isJoinedOnce: Boolean
        get() {
            return _joinRef != null
        }

    private fun <T> nonJoinedFailure(): Result<T> =
        Result.failure(BadActionException("Pushing message on non-joined channel is not allowed"))

    override suspend fun pushNoReply(event: String, payload: JsonElement): Result<Unit> =
        pushNoReply(event, payload, defaultTimeout)

    override suspend fun pushNoReply(event: String, payload: JsonElement, timeout: Long): Result<Unit> =
        if (isJoinedOnce) {
            sendToSocket(event, payload, timeout.toDynamicTimeout(), true).map { }
        } else {
            nonJoinedFailure()
        }

    override suspend fun push(event: String, payload: JsonElement) =
        push(event, payload, defaultTimeout)

    override suspend fun push(event: String, payload: JsonElement, timeout: Long)
            : Result<Reply> =
        if (isJoinedOnce) {
            sendToSocket(event, payload, timeout.toDynamicTimeout(), false).fold(
                { it!!.toReply() }, { Result.failure(it) }
            )
        } else {
            nonJoinedFailure()
        }

    override suspend fun leave() {
        disposeFromSocket(topic)
    }

    internal fun dirtyClose() {
        logger.d { "Dirty closing channel with topic '$topic'" }
        _state.update { ChannelState.CLOSE }
    }

    internal suspend fun close(timeout: Long = defaultTimeout) {
        // Atomic transition to LEAVING: concurrent closes must send a single phx_leave, and must not clear
        // the join ref while it is being sent.
        var previous: ChannelState
        while (true) {
            previous = state.value
            when (previous) {
                ChannelState.CLOSE -> {
                    _joinRef = null
                    return
                }

                ChannelState.LEAVING -> {
                    // Another close is in progress: return once the channel is closed.
                    state.first { it == ChannelState.CLOSE }
                    return
                }

                else -> if (_state.compareAndSet(previous, ChannelState.LEAVING)) break
            }
        }

        try {
            // Send phx_leave before clearing the join ref: the server drops it without the current join_ref.
            if (previous == ChannelState.JOINED) {
                // Don't care about the result.
                sendToSocket("phx_leave", emptyPayload, timeout.toDynamicTimeout(), false)
            }
        } finally {
            // Also when cancelled, so that the channel never stays LEAVING.
            _joinRef = null
            _state.update { ChannelState.CLOSE }
        }
    }

    internal suspend fun rejoin(timeout: DynamicTimeout = DEFAULT_REJOIN_TIMEOUT): Result<Channel> =
        if (_joinRef == null) {
            Result.failure(BadActionException("Channel with topic '$topic' was never joined"))
        } else {
            join(joinPayload ?: emptyPayload, timeout)
        }

    internal suspend fun join(
        payload: JsonElement = emptyPayload,
        timeout: DynamicTimeout = DEFAULT_REJOIN_TIMEOUT,
    ): Result<Channel> {
        // Atomic transition: concurrent joins must send a single phx_join. Otherwise the server closes the first
        // session, and the channel may keep its join ref: its pushes and phx_leave are then dropped.
        if (!_state.compareAndSet(ChannelState.CLOSE, ChannelState.JOINING)) {
            return Result.failure(
                BadActionException(
                    when (state.value) {
                        ChannelState.LEAVING -> "Channel with topic '$topic' is leaving"
                        ChannelState.JOINED -> "Channel with topic '$topic' is already joined"
                        else -> "Channel with topic '$topic' is already joining"
                    }
                )
            )
        }

        joinPayload = payload

        return sendToSocket("phx_join", payload, timeout, false).map { it!! }
            .onSuccess {
                logger.d { "Channel with topic '$topic' was joined" }
                // Set the join ref before publishing JOINED: pushes waiting on that state
                // read it right away, and the server drops messages with a stale join_ref.
                _joinRef = it.ref
                _state.update { ChannelState.JOINED }
            }
            .onFailure {
                // Back to CLOSE so that the channel can be joined again.
                _state.update { ChannelState.CLOSE }
                logger.e { "Failed to join channel with '$topic': " + it.stackTraceToString() }
            }
            .map { this }
    }
}