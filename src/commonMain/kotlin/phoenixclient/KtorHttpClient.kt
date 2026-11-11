package phoenixclient

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.encodedPath
import io.ktor.util.toMap
import kotlinx.coroutines.CancellationException
import phoenixclient.engine.createPlatformHttpClient
import io.ktor.http.HttpMethod as KtorHttpMethod


/**
 * HTTP client of the platform (OkHttp on the JVM, Darwin on iOS), created by [phoenixHttpClient].
 *
 * Pass it to [ktorPhoenixClient] to run the WebSocket of [server] on it, and use it as [PhoenixHttpClient]
 * for the application's HTTP requests to the same server, with the same certificate trust.
 * phoenix-client never closes it: [close] it once neither the socket nor the application use it.
 */
class PlatformHttpClient internal constructor(
    override val server: HttpServer,
    internal val ktor: KtorClient,
) : HttpClient, AutoCloseable {

    override suspend fun request(request: HttpRequest): HttpResponse {
        val response = try {
            ktor.client.request {
                method = KtorHttpMethod.parse(request.method.name)
                url {
                    protocol = if (server.ssl) URLProtocol.HTTPS else URLProtocol.HTTP
                    host = server.host
                    port = server.port
                    encodedPath = request.path
                    request.query.forEach { (key, value) -> parameters.append(key, value) }
                }
                request.headers.forEach { (key, value) -> header(key, value) }
                request.body?.let { setBody(ByteArrayContent(it.content, ContentType.parse(it.contentType))) }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw HttpRequestException("${request.method} ${request.path} failed: ${ex.message}", ex)
        }

        val body = try {
            response.bodyAsBytes()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw HttpRequestException("${request.method} ${request.path}: failed to read the response", ex)
        }

        return HttpResponse(response.status.value, response.headers.toMap(), body)
    }

    override fun close() = ktor.client.close()
}

/** Creates the HTTP client of the platform for [server] (see [PlatformHttpClient]). */
fun phoenixHttpClient(server: PhoenixServer): PlatformHttpClient =
    PlatformHttpClient(server, createPlatformHttpClient(server.untrustedCertificate))
