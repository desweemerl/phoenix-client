package phoenixclient

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import phoenixclient.engine.KtorClient
import phoenixclient.engine.KtorHttpEngine
import phoenixclient.engine.PhoenixHttpBody
import phoenixclient.engine.PhoenixHttpServer
import phoenixclient.engine.delete
import phoenixclient.engine.get
import phoenixclient.engine.post
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Item(val name: String, val count: Int = 0)

class HttpTest {
    // Real time: the mock engine runs on its own dispatcher, which the virtual time of runTest doesn't wait for,
    // so the timeout of the request would expire at once.
    private fun runHttpTest(body: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { body() }
    }

    private val requests = mutableListOf<HttpRequestData>()

    private fun engine(
        server: PhoenixHttpServer = PhoenixHttpServer("example.com", 4001),
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        headers: Map<String, List<String>> = emptyMap(),
    ) = KtorHttpEngine(
        server,
        KtorClient(HttpClient(MockEngine { request ->
            requests += request
            respond(body, status, headersOf(*headers.toList().toTypedArray()))
        })),
    )

    @Test
    fun requestsThePathOfTheServer() = runHttpTest {
        engine().get("/api/v1/items", query = listOf("q" to "a b"), headers = mapOf("X-Token" to "token"))

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://example.com:4001/api/v1/items?q=a+b", request.url.toString())
        assertEquals("token", request.headers["X-Token"])
    }

    @Test
    fun sendsThePathAsIsAndRepeatedQueryParameters() = runHttpTest {
        engine().get("/items/a%20b", query = listOf("ids[]" to "1", "ids[]" to "2"))

        val url = requests.single().url
        assertEquals("/items/a%20b", url.encodedPath)
        assertEquals(listOf("1", "2"), url.parameters.getAll("ids[]"))
    }

    @Test
    fun requestsWithoutSsl() = runHttpTest {
        engine(PhoenixHttpServer("localhost", 4000, ssl = false)).delete("/items/1")

        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("http://localhost:4000/items/1", request.url.toString())
    }

    @Test
    fun sendsTheBodyWithItsContentType() = runHttpTest {
        engine().post("/items", PhoenixHttpBody.json(Item("a", 2), Item.serializer()))

        val body = requests.single().body as OutgoingContent.ByteArrayContent
        assertEquals("application/json", body.contentType.toString())
        assertEquals("""{"name":"a","count":2}""", body.bytes().decodeToString())
    }

    @Test
    fun returnsTheResponseOfAnyStatus() = runHttpTest {
        val response = engine(
            status = HttpStatusCode.Unauthorized,
            body = """{"name":"a","unknown":true}""",
            headers = mapOf("Content-Type" to listOf("application/json")),
        ).post("/items")

        assertEquals(401, response.status)
        assertFalse(response.isSuccess)
        assertEquals("application/json", response.header("content-type"))
        assertNull(response.header("X-Missing"))
        // Unknown keys are ignored by default
        assertEquals(Item("a"), response.json(Item.serializer()))
    }

    @Test
    fun returnsTheBodyAsBytes() = runHttpTest {
        val response = engine(body = "abc").get("/")

        assertTrue(response.isSuccess)
        assertContentEquals("abc".encodeToByteArray(), response.body)
        assertEquals("abc", response.text)
    }

    @Test
    fun aFailedRequestThrowsHttpRequestException() = runHttpTest {
        val engine = KtorHttpEngine(
            PhoenixHttpServer("example.com"),
            KtorClient(HttpClient(MockEngine { error("unreachable") })),
        )

        assertFailsWith<HttpRequestException> { engine.get("/") }
    }

    @Test
    fun aRequestWithoutResponseTimesOut() = runHttpTest {
        val engine = KtorHttpEngine(
            PhoenixHttpServer("example.com"),
            KtorClient(HttpClient(MockEngine {
                delay(10_000)
                respond("")
            })),
        )

        val failure = assertFailsWith<HttpRequestException> { engine.get("/", timeout = 100) }
        assertTrue(failure.message!!.contains("timed out"))
    }
}
