package phoenixclient

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import phoenixclient.engine.KtorHttpEngine
import kotlin.time.Duration.Companion.minutes

// Port of the Phoenix test server in /test.
const val TEST_SERVER_PORT = 4000

// Replaces JUnit's @RepeatedTest, which kotlin.test doesn't have.
fun runRepeatedTest(times: Int, body: suspend TestScope.() -> Unit): TestResult =
    runTest(timeout = 5.minutes) {
        repeat(times) { body() }
    }

// Base of the integration tests, which run against the Phoenix test server.
abstract class EngineTest {
    val testServer = PhoenixHttpServer("localhost", TEST_SERVER_PORT, ssl = false)

    fun createEngine(): PhoenixHttpEngine = KtorHttpEngine(testServer)

    init {
        // Print logs in the test reports on every platform (Kermit writes to OSLog on iOS).
        Logger.setLogWriters(CommonWriter())
    }
}
