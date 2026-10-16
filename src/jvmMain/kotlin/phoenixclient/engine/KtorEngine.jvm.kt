package phoenixclient.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

// OkHttp's exception carries the status of a refused handshake, no need to record it.
internal actual fun createPlatformHttpClient(untrustedCertificate: Boolean): PlatformHttpClient =
    PlatformHttpClient(
        HttpClient(OkHttp) {
            engine {
                preconfigured = if (untrustedCertificate) getUntrustedOkHttpClient() else getHttpClient()
            }
            install(WebSockets)
        }
    )
