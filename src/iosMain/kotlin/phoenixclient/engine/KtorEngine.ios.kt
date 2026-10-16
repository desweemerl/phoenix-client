package phoenixclient.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.ChallengeHandler
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

// Darwin's exception for a refused WebSocket handshake doesn't carry the HTTP status (NSURLErrorBadServerResponse
// for 403, 404, 502...). This delegate records it from the failed task, then forwards every callback to Ktor's
// delegate, as documented by KtorNSURLSessionDelegate for custom session delegates.
@OptIn(UnsafeNumber::class)
private class StatusRecordingDelegate(
    private val ktorDelegate: KtorNSURLSessionDelegate,
) : NSObject(), NSURLSessionDataDelegateProtocol, NSURLSessionWebSocketDelegateProtocol {

    @Volatile
    var lastFailedStatus: Int? = null

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (didCompleteWithError != null) {
            // Recorded before Ktor completes the request with an exception.
            lastFailedStatus = (task.response as? NSHTTPURLResponse)?.statusCode?.toInt()
        }
        ktorDelegate.URLSession(session, task, didCompleteWithError)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) =
        ktorDelegate.URLSession(session, dataTask, didReceiveData)

    override fun URLSession(session: NSURLSession, taskIsWaitingForConnectivity: NSURLSessionTask) =
        ktorDelegate.URLSession(session, taskIsWaitingForConnectivity = taskIsWaitingForConnectivity)

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didOpenWithProtocol: String?,
    ) = ktorDelegate.URLSession(session, webSocketTask, didOpenWithProtocol)

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didCloseWithCode: NSURLSessionWebSocketCloseCode,
        reason: NSData?,
    ) = ktorDelegate.URLSession(session, webSocketTask, didCloseWithCode, reason)

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) = ktorDelegate.URLSession(session, task, willPerformHTTPRedirection, newRequest, completionHandler)

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) = ktorDelegate.URLSession(session, task, didReceiveChallenge, completionHandler)
}

// Accept any server certificate. The app also needs an App Transport Security exception.
// Opt-in: the server trust is a C pointer (SecTrustRef). convert(): the disposition is an NSInteger.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private val trustAnyCertificate: ChallengeHandler = { _, _, challenge, completionHandler ->
    val protectionSpace = challenge.protectionSpace
    val serverTrust = protectionSpace.serverTrust

    if (protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust && serverTrust != null) {
        completionHandler(NSURLSessionAuthChallengeUseCredential.convert(), NSURLCredential.credentialForTrust(serverTrust))
    } else {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
    }
}

@OptIn(UnsafeNumber::class)
internal actual fun createPlatformHttpClient(untrustedCertificate: Boolean): PlatformHttpClient {
    val ktorDelegate = KtorNSURLSessionDelegate(if (untrustedCertificate) trustAnyCertificate else null)
    val delegate = StatusRecordingDelegate(ktorDelegate)

    // Same configuration as the session created by Ktor.
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        setHTTPCookieStorage(null)
    }
    val session = NSURLSession.sessionWithConfiguration(configuration, delegate, delegateQueue = null)

    val client = HttpClient(Darwin) {
        engine {
            usePreconfiguredSession(session, ktorDelegate)
        }
        install(WebSockets)
    }

    return PlatformHttpClient(client) { delegate.lastFailedStatus }
}
