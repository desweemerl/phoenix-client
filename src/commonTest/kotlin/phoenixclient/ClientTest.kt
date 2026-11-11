package phoenixclient

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*


class ClientTest : EngineTest() {

    @Test
    @ExperimentalCoroutinesApi
    fun testUnauthorizedConnection() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                var forbidden = false
                val client = getClient()
                val job = launch {
                    forbidden = client.messages.filter { it == Forbidden }.map { true }.first()
                }

                client.connect(mapOf("token" to "wrongToken"))
                job.join()
                client.disconnect()

                assertTrue(forbidden)
                assertTrue(!client.active)
            }
        }
    }

    @Serializable
    private data class Echo(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val content_type: String? = null,
        val token: String? = null,
        val body: String,
    )

    @Test
    @ExperimentalCoroutinesApi
    fun testSharedEngine() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val engine = createEngine()

                // Shared with the application: it must still report a refused handshake as Forbidden (Darwin)
                val refused = phoenixClient(engine).getOrThrow()
                val forbidden = async(start = CoroutineStart.UNDISPATCHED) {
                    refused.messages.filter { it == Forbidden }.first()
                }

                refused.connect(mapOf("token" to "wrongToken"))
                forbidden.await()
                refused.disconnect()
                assertTrue(!refused.active)

                // It serves the application's HTTP requests to the same server
                val response = engine.post(
                    "/api/items",
                    PhoenixHttpBody.text("""{"a":1}""", "application/json"),
                    headers = mapOf("X-Token" to "token"),
                )
                assertEquals(200, response.status)
                assertEquals(
                    Echo("POST", "/api/items", emptyMap(), "application/json", "token", """{"a":1}"""),
                    response.json(Echo.serializer()),
                )

                val query = engine.get("/status/401", query = listOf("q" to "a b"))
                assertEquals(401, query.status)
                assertEquals(mapOf("q" to "a b"), query.json(Echo.serializer()).query)

                // And several connections: disconnecting a client leaves the other one connected
                val client1 = phoenixClient(engine).getOrThrow()
                val client2 = phoenixClient(engine).getOrThrow()
                client1.connect(mapOf("token" to "user1234"))
                client2.connect(mapOf("token" to "user1234"))
                client1.state.waitConnected()
                client2.state.waitConnected()

                client1.disconnect()
                client1.state.waitDisconnected()
                val channel = client2.join("test:1").getOrThrow()
                assertTrue(channel.push("get_headers").isSuccess)
                channel.leave()

                // Closing the engine closes the remaining connection
                engine.close()
                client2.state.waitDisconnected()
                client2.disconnect()
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testFailedConnectionCarriesTheStatus() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val engine = createEngine()

                val failure = engine.connectWebSocket(DEFAULT_SOCKET_PATH, mapOf("token" to "wrongToken")) {
                    fail("no event is expected for a connection which failed to open")
                }.exceptionOrNull()

                assertIs<WebSocketConnectException>(failure)
                assertTrue(failure.isForbidden)

                engine.close()
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testRefusedConnectionIsRetried() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                // The server refuses the upgrade on an unknown path, but not with 403.
                val client = getClient(path = "/unknown")
                val failure = async(start = CoroutineStart.UNDISPATCHED) {
                    client.messages.filter { it == Failure || it == Forbidden }.first()
                }

                client.connect(mapOf("token" to "user1234"))
                val message = failure.await()

                // Unlike Forbidden, a failure keeps the client active: it reconnects.
                assertEquals(Failure, message)
                assertTrue(client.active)

                client.disconnect()
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testAuthorizedConnection() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var isConnected = false
                val job = launch {
                    isConnected = client.state.waitConnected()
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()
                client.disconnect()

                assertTrue(isConnected)
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testHeartbeat() = runTest {
        withContext(Dispatchers.Default) {
            val client = getClient(
                heartbeatTimeout = 1000L,
                heartbeatInterval = 1000L,
                defaultTimeout = 1000L,
            )

            var counter = 0

            val job = launch {
                client.messages
                    .filter { it.topic == "phoenix" && it.event == "phx_reply" }
                    .collect {
                        counter++
                    }
            }

            client.connect(mapOf("token" to "user1234"))
            client.state.waitConnected()

            delay(4000L)
            val refCounter = counter

            client.disconnect()
            client.state.waitDisconnected()

            delay(4000L)
            assertTrue(counter > 0 && refCounter == counter)

            client.connect(mapOf("token" to "user1234"))
            client.state.waitConnected()

            delay(4000L)
            assertTrue(refCounter != counter)

            client.disconnect()
            job.cancel()
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testHeadersConnection() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var reply: Reply? = null

                val job = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()
                    reply = channel.push("get_headers").getOrThrow()
                }

                client.connect(
                    params = mapOf("token" to "user1234"),
                    headers = mapOf("X-Header1" to "value1", "X-Header2" to "value2"),
                )
                job.join()
                client.disconnect()

                val values = reply?.convertTo<Map<String, String>>()?.getOrThrow()
                assertTrue(values?.get("header1") == "value1")
                assertTrue(values?.get("header2") == "value2")
            }
        }
    }

    private fun getClient(
        path: String = DEFAULT_SOCKET_PATH,
        heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL,
        heartbeatTimeout: Long = DEFAULT_HEARTBEAT_TIMEOUT,
        defaultTimeout: Long = DEFAULT_TIMEOUT,
    ): Client =
        phoenixClient(
            httpEngine = createEngine(),
            path = path,
            heartbeatInterval = heartbeatInterval,
            heartbeatTimeout = heartbeatTimeout,
            defaultTimeout = defaultTimeout,
        ).getOrThrow()
}
