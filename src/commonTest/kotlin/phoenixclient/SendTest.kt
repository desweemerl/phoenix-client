package phoenixclient

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

// Engine that replies synchronously inside send(), followed by an unrelated message, so a reply
// is only received if the client listens for it before sending the request.
private class InstantReplyEngine : PhoenixHttpEngine {
    override val server = PhoenixHttpServer("localhost")

    override suspend fun request(request: PhoenixHttpRequest): PhoenixHttpResponse =
        throw HttpRequestException("not supported")

    override suspend fun connectWebSocket(
        path: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        receiver: (event: PhoenixWebSocketEvent) -> Unit,
    ): Result<PhoenixWebSocketConnection> =
        Result.success(object : PhoenixWebSocketConnection {
            override fun send(message: OutgoingMessage): Result<Unit> {
                val joinRef = message.joinRef?.let { "\"$it\"" } ?: "null"
                val reply =
                    """[$joinRef,"${message.ref}","${message.topic}","phx_reply",{"status":"ok","response":{}}]"""
                receiver(PhoenixWebSocketEvent(message = fromJson(reply)))
                receiver(PhoenixWebSocketEvent(message = fromJson("""[null,null,"other","noise",{}]""")))
                return Result.success(Unit)
            }

            override fun close() {
                receiver(PhoenixWebSocketEvent(state = ConnectionState.DISCONNECTED))
            }
        })

    override fun close() {}
}

class SendTest {

    @Test
    @ExperimentalCoroutinesApi
    fun testReplyReceivedBeforeListening() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = phoenixClient(InstantReplyEngine()).getOrThrow()

                client.connect()
                val channel = client.join("test:1").getOrThrow()
                assertTrue(channel.push("hello").isSuccess)

                client.disconnect()
            }
        }
    }

    @Test
    fun testRefGeneratorIsUniqueAcrossThreads() = runTest {
        val nextRef = refGenerator()

        val refs = (1..8).map {
            async(Dispatchers.Default) {
                List(10_000) { nextRef() }
            }
        }.awaitAll().flatten().toSet()

        assertTrue(refs.size == 80_000, "only ${refs.size} unique refs out of 80000")
    }
}
