package phoenixclient.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// The client retries the connection itself.
private fun getHttpClient(): OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

private fun getUntrustedOkHttpClient(): OkHttpClient {
    val x509UntrustManager = object : X509TrustManager {
        override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        }

        override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return arrayOf()
        }
    }

    val trustAllCerts = arrayOf(x509UntrustManager)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
        null,
        trustAllCerts,
        SecureRandom()
    )

    val sslSocketFactory = sslContext.socketFactory

    return OkHttpClient.Builder()
        .sslSocketFactory(sslSocketFactory, trustAllCerts[0])
        .hostnameVerifier { _, _ -> true }
        .retryOnConnectionFailure(false)
        .build()
}

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
