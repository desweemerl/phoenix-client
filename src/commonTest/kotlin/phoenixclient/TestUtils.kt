package phoenixclient

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import phoenixclient.engine.KtorEngine
import phoenixclient.engine.WebSocketEngine
import kotlin.time.Duration.Companion.minutes

// Port of the Phoenix test server in /test.
const val TEST_SERVER_PORT = 4000

// Replaces JUnit's @RepeatedTest, which kotlin.test doesn't have.
fun runRepeatedTest(times: Int, body: suspend TestScope.() -> Unit): TestResult =
    runTest(timeout = 5.minutes) {
        repeat(times) { body() }
    }

// Integration tests run against every engine: subclasses provide the engine.
abstract class EngineTest {
    abstract fun createEngine(): WebSocketEngine

    init {
        // Print logs in the test reports on every platform (Kermit writes to OSLog on iOS).
        Logger.setLogWriters(CommonWriter())
    }
}

class KtorClientTest : ClientTest() {
    override fun createEngine(): WebSocketEngine = KtorEngine()
}

class KtorChannelTest : ChannelTest() {
    override fun createEngine(): WebSocketEngine = KtorEngine()
}

class KtorDeserializationTest : DeserializationTest() {
    override fun createEngine(): WebSocketEngine = KtorEngine()
}
