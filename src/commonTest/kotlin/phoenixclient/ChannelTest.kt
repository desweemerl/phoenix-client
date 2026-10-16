package phoenixclient

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
class ReplyMessage(val message: String)

class ChannelTest : EngineTest() {

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelJoinAndRef() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var message1: Reply? = null
                var message2: IncomingMessage? = null

                val job1 = launch {
                    client.state.waitConnected()

                    message1 = client
                        .join("test:1").getOrThrow()
                        .push("hello", TestPayload(name = "toto")).getOrThrow()
                }

                val job2 = launch {
                    client.messages.collect { message2 = it }
                }

                client.connect(mapOf("token" to "user1234"))

                job1.join()
                job2.cancel()
                client.disconnect()

                assertTrue(message1?.convertTo<ReplyMessage>()?.getOrNull()?.message == "hello toto")
                assertTrue(Regex("""^\d+$""").matches(message2?.ref!!))
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelDisconnection() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var connection = 0

                client.connect(mapOf("token" to "user1234"))
                val channel = client.join("test:1").getOrThrow()

                val disconnectionJob = launch {
                    client.state.isConnected()
                        .takeWhile { connection < 3 }
                        .collect {
                            connection++
                            launch {
                                channel.pushNoReply("close_socket")
                            }
                        }
                }

                disconnectionJob.join()
                client.disconnect()

                assertTrue(connection == 3)
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelCrashRejoin() = runRepeatedTest(10) {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var countJoined = 0

                val job = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()

                    channel.state
                        .takeWhile { countJoined < 2 }
                        .filter { it == ChannelState.JOINED }
                        .collect {
                            countJoined++
                            channel.pushNoReply("crash_channel")
                        }
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()
                client.disconnect()

                assertTrue(countJoined == 2)
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelWrongChannel() = runRepeatedTest(10) {
        withContext(Dispatchers.Default) {
            var joinRef2: String? = null
            var resultRef: String? = null

            withTimeout(5000) {
                val client = getClient()

                val job1 = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()
                    val joinRef1 = channel.joinRef

                    assertTrue(channel.push("wrong_request").exceptionOrNull() is ChannelException)

                    assertTrue(channel.push("hello", TestPayload(name = "toto"), 100L).isSuccess)
                    joinRef2 = channel.joinRef
                    assertTrue(joinRef1 != null && joinRef2 != null && joinRef1 != joinRef2)
                }

                val job2 = launch {
                    resultRef = client.messages
                        .filter { message ->
                            val payload = message.payload?.convertTo<ReplyMessage>("response")?.getOrNull()
                            payload?.message == "hello toto"
                        }
                        .map { it.joinRef }
                        .first()
                }

                client.connect(mapOf("token" to "user1234"))
                job1.join()
                job2.join()

                assertTrue(joinRef2 == resultRef)

                client.disconnect()
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelBatch() = runRepeatedTest(10) {
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                val client = getClient()
                var counter1 = 0
                var counter2 = 0
                val size = 1000

                val job1 = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()

                    repeat(size) {
                        val name = "toto$counter1"
                        channel.push("hello", TestPayload(name = name), 100L).getOrThrow()
                        counter1++
                    }
                }

                val job2 = launch {
                    client.messages
                        .collect {
                            if (it.topic == "test:1" && it.joinRef != null) {
                                counter2++
                                if (counter2 == size) {
                                    cancel()
                                }
                            }
                        }
                }

                client.connect(mapOf("token" to "user1234"))
                joinAll(job1, job2)
                client.disconnect()
                println("counter1: $counter1 counter2: $counter2")

                assertTrue(counter1 == size)
                assertTrue(counter2 == size)
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelMessages() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                val client = getClient()
                var reply: Reply? = null

                val job = launch {
                    client.state.waitConnected()
                    val channel = client.join("test:1").getOrThrow()
                    reply = channel.push("hello", TestPayload(name = "toto"), 100L).getOrThrow()
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()
                client.disconnect()

                assertTrue(
                    reply?.convertTo<ReplyMessage>()?.getOrNull()?.message == "hello toto"
                )
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelLeave() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                client.connect(mapOf("token" to "user1234"))
                val channel = client.join("test:1").getOrThrow()

                // Subscribe before leaving so that the reply to phx_leave cannot be missed.
                val leaveReply = async(start = CoroutineStart.UNDISPATCHED) {
                    client.messages
                        .filter { it.topic == "test:1" && it.event == "phx_reply" }
                        .first()
                }

                channel.leave()

                // The server only replies when phx_leave was sent with the current join_ref.
                assertTrue(leaveReply.await().hasStatus("ok"))
                assertTrue(channel.state.first() == ChannelState.CLOSE)

                // The topic can be joined again after leaving.
                val rejoined = client.join("test:1").getOrThrow()
                assertTrue(rejoined.push("hello", TestPayload(name = "toto")).isSuccess)

                client.disconnect()
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testDisconnectWithMultipleChannels() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                client.connect(mapOf("token" to "user1234"))
                val channels = listOf("test:1", "test:2", "test:3").map { client.join(it).getOrThrow() }

                client.disconnect()

                channels.forEach { assertTrue(it.state.first() == ChannelState.CLOSE) }
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelJoinRefused() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                client.connect(mapOf("token" to "user1234"))

                // A refused join must not leave the channel stuck in JOINING: joining again
                // gets the server's refusal again, not "already joining".
                repeat(2) {
                    val exception = client.join("test:refused").exceptionOrNull()
                    assertTrue(exception is ResponseException, "attempt $it: $exception")
                }

                client.disconnect()
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testChannelConcurrentPushes() = runRepeatedTest(5) {
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                val client = getClient()
                client.connect(mapOf("token" to "user1234"))
                val channel = client.join("test:1").getOrThrow()

                // Each push must get its own reply, even when many are in flight at once.
                val replies = (1..500).map { i ->
                    async {
                        i to channel.push("hello", TestPayload(name = "toto$i"), 2000L)
                    }
                }.awaitAll()

                client.disconnect()

                replies.forEach { (i, reply) ->
                    val message = reply.getOrThrow().convertTo<ReplyMessage>().getOrThrow().message
                    assertTrue(message == "hello toto$i", "push $i got '$message'")
                }
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testConcurrentJoins() = runRepeatedTest(5) {
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                val client = getClient()
                client.connect(mapOf("token" to "user1234"))
                client.state.waitConnected()

                // Many topics joined at once: every channel is stored and usable.
                val channels = (1..50).map { i ->
                    async { client.join("test:$i").getOrThrow() }
                }.awaitAll()

                channels.map { async { it.push("hello", TestPayload(name = "toto")) } }
                    .awaitAll()
                    .forEachIndexed { i, reply -> assertTrue(reply.isSuccess, "push on test:${i + 1}: $reply") }

                // The same topic joined at once: a single channel. The other joins may fail with "already joining".
                val sameTopic = (1..20).map { async { client.join("test:same") } }.awaitAll()
                assertEquals(1, sameTopic.mapNotNull { it.getOrNull() }.distinct().size)

                client.disconnect()

                channels.forEach { assertEquals(ChannelState.CLOSE, it.state.first()) }
            }
        }
    }

    private fun getClient(
        retry: DynamicTimeout = DEFAULT_RETRY,
        heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL
    ): Client =
        phoenixClient(
            webSocketEngine = createEngine(),
            port = TEST_SERVER_PORT,
            ssl = false,
            retryTimeout = retry,
            heartbeatInterval = heartbeatInterval,
        ).getOrThrow()
}
