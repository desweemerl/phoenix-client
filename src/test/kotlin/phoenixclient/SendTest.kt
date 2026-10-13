package phoenixclient

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import phoenixclient.engine.WebSocketEngine
import phoenixclient.engine.WebSocketEvent
import java.util.concurrent.ConcurrentHashMap

// Engine that replies synchronously inside send(), followed by an unrelated message, so a reply
// is only received if the client listens for it before sending the request.
private class InstantReplyEngine : WebSocketEngine {
    private var receiver: (WebSocketEvent) -> Unit = {}

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
        this.receiver = receiver
        receiver(WebSocketEvent(state = ConnectionState.CONNECTED))
    }

    override fun send(message: OutgoingMessage): Result<Unit> {
        val joinRef = message.joinRef?.let { "\"$it\"" } ?: "null"
        val reply = """[$joinRef,"${message.ref}","${message.topic}","phx_reply",{"status":"ok","response":{}}]"""
        receiver(WebSocketEvent(message = fromJson(reply)))
        receiver(WebSocketEvent(message = fromJson("""[null,null,"other","noise",{}]""")))
        return Result.success(Unit)
    }

    override fun close() {
        receiver(WebSocketEvent(state = ConnectionState.DISCONNECTED))
    }
}

class SendTest {

    @Test
    @ExperimentalCoroutinesApi
    fun testReplyReceivedBeforeListening() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = ClientImpl(
                    webSocketEngine = InstantReplyEngine(),
                    dispatcher = Dispatchers.Default,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                )

                client.connect()
                val channel = client.join("test:1").getOrThrow()
                assert(channel.push("hello").isSuccess)

                client.disconnect()
            }
        }
    }

    @Test
    fun testRefGeneratorIsUniqueAcrossThreads() = runBlocking {
        val nextRef = refGenerator()
        val refs = ConcurrentHashMap.newKeySet<Long>()

        (1..8).map {
            launch(Dispatchers.Default) {
                repeat(10_000) { refs.add(nextRef()) }
            }
        }.joinAll()

        assert(refs.size == 80_000) { "only ${refs.size} unique refs out of 80000" }
    }
}
