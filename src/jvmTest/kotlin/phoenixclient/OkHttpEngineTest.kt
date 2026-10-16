package phoenixclient

import phoenixclient.engine.OkHttpEngine
import phoenixclient.engine.WebSocketEngine

class OkHttpClientTest : ClientTest() {
    override fun createEngine(): WebSocketEngine = OkHttpEngine()
}

class OkHttpChannelTest : ChannelTest() {
    override fun createEngine(): WebSocketEngine = OkHttpEngine()
}

class OkHttpDeserializationTest : DeserializationTest() {
    override fun createEngine(): WebSocketEngine = OkHttpEngine()
}
