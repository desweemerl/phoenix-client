package phoenixclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DeserializationTest {

    @Serializable
    data class TestReplyObject(
        @SerialName("value_string")
        val valueString: String,
        @SerialName("value_number")
        val valueNumber: Float,
        @SerialName("value_boolean")
        val valueBoolean: Boolean,
    )

    @Serializable
    data class TestListReplyObject(
        val list: List<TestReplyObject>
    )

    @Test
    @ExperimentalCoroutinesApi
    fun testDeserializeObject() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var response: TestReplyObject? = null

                val job = launch {
                    client.state.waitConnected()

                    val reply = client
                        .join("test:1").getOrThrow()
                        .push("deserialize_object")
                        .getOrThrow()
                    response = reply.convertTo<TestReplyObject>().getOrThrow()
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()

                assert(
                    response == TestReplyObject(
                        valueString = "test1234",
                        valueNumber = -1234.5678f,
                        valueBoolean = true,
                    )
                )
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testDeserializeList() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var response: TestListReplyObject? = null

                val job = launch {
                    client.state.waitConnected()

                    val reply = client
                        .join("test:1").getOrThrow()
                        .push("deserialize_list")
                        .getOrThrow()
                    response = reply.convertTo<TestListReplyObject>().getOrThrow()
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()

                val expected = listOf("_1", "_2").map {
                    TestReplyObject(
                        valueString = "test1234${it}",
                        valueNumber = -1234.5678f,
                        valueBoolean = true,
                    )
                }
                assert(response?.list == expected)
            }
        }
    }

    @Test
    @ExperimentalCoroutinesApi
    fun testDeserializeListFailed() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var response: Result<TestListReplyObject>? = null

                val job = launch {
                    client.state.waitConnected()

                    val reply = client
                        .join("test:1").getOrThrow()
                        .push("deserialize_list_failed")
                        .getOrThrow()
                    response = reply.convertTo<TestListReplyObject>()
                }

                client.connect(mapOf("token" to "user1234"))
                job.join()

                // The response is a list, not an object with a "list" key.
                val exception = response!!.exceptionOrNull()
                assert(exception is SerializationException) { "unexpected result: $response" }
            }
        }

    }

    @Test
    @ExperimentalCoroutinesApi
    fun testDeserializeEvent() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5000) {
                val client = getClient()
                var response: TestReplyObject? = null

                val job1 = launch {
                    client.state.waitConnected()
                    client
                        .join("test:1").getOrThrow()
                        .pushNoReply("deserialize_event")
                }

                val job2 = launch {
                    response = client.messages
                        .filterEvent("test_event").first()
                        .payload!!.convertTo<TestReplyObject>().getOrThrow()
                }

                client.connect(mapOf("token" to "user1234"))
                joinAll(job1, job2)

                assert(
                    response == TestReplyObject(
                        valueString = "test1234",
                        valueNumber = -1234.5678f,
                        valueBoolean = true,
                    )
                )
            }
        }
    }

    private fun getClient(
        retry: DynamicTimeout = DEFAULT_RETRY,
        heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL
    ): Client =
        okHttpPhoenixClient(
            port = 4000,
            ssl = false,
            retryTimeout = retry,
            heartbeatInterval = heartbeatInterval,
        ).getOrThrow()
}
