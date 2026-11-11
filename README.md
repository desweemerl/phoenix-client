# phoenix-client

A Kotlin Multiplatform client for [Phoenix Channels](https://hexdocs.pm/phoenix/channels.html), built on
coroutines, `Flow` and [Ktor](https://ktor.io) WebSockets.

- **Platforms:** JVM (also used by Android) and iOS (`iosArm64`, `iosSimulatorArm64`)
- **Protocol:** Phoenix serializer `2.0.0` (`[join_ref, ref, topic, event, payload]`)
- **Resilient:** reconnects with backoff, sends heartbeats, and rejoins channels after a reconnection or a
  channel crash
- **Typed payloads** with `kotlinx.serialization`

## Installation

The library is published to GitHub Packages, which requires a GitHub token with the `read:packages` scope:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/desweemerl/phoenix-client")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts of a Kotlin Multiplatform module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.desweemerl:phoenix-client:<version>")
        }
    }
}
```

Payload classes are serialized with `kotlinx.serialization`, so the module also needs the serialization plugin:

```kotlin
plugins {
    kotlin("plugin.serialization") version "<kotlin version>"
}
```

The JVM artifact is compiled for Java 11.

## Usage

```kotlin
@Serializable
data class Hello(val name: String)

@Serializable
data class HelloReply(val message: String)

suspend fun hello() {
    val engine = KtorHttpEngine(PhoenixHttpServer(host = "example.com"))
    val client = phoenixClient(engine).getOrThrow()

    // Connects in the background, and reconnects whenever the connection is lost.
    client.connect(params = mapOf("token" to "user1234"))

    // Waits for the connection, then joins.
    val channel = client.join("room:lobby").getOrThrow()

    val reply = channel.push("hello", Hello(name = "toto")).getOrThrow()
    println(reply.convertTo<HelloReply>().getOrThrow().message)

    client.disconnect()
    engine.close()
}
```

On the Phoenix side, this matches a channel such as:

```elixir
def handle_in("hello", %{"name" => name}, socket) do
  {:reply, {:ok, %{message: "hello #{name}"}}, socket}
end
```

### Creating a client

`KtorHttpEngine(server)` creates the engine of a server, running on the HTTP client of the platform (OkHttp on the
JVM, `NSURLSession` (Darwin) on iOS):

| `PhoenixHttpServer` | Default | Description |
|---|---|---|
| `host` | | Server host |
| `port` | `443` | Server port |
| `ssl` | `true` | `wss://` and `https://`, or `ws://` and `http://` |
| `untrustedCertificate` | `false` | Accept any server certificate (development only) |

`phoenixClient(engine, ...)` returns a `Result<Client>` running its WebSocket on that engine. Other parameters are
optional:

| Parameter | Default | Description |
|---|---|---|
| `path` | `"/socket"` | Socket path, as in `socket "/socket", ...` in the endpoint (`/websocket` is appended) |
| `retryTimeout` | 10 ms, 50 ms … 2 s, then 5 s | Delay between reconnection attempts, by attempt number |
| `heartbeatInterval` | 30 s | Interval between heartbeats; must be ≥ `defaultTimeout` |
| `heartbeatTimeout` | 1 s | A heartbeat without reply within this delay closes the connection, which is then reopened |
| `defaultTimeout` | 10 s | Timeout of `push` and `leave` |
| `dispatcher` | `Dispatchers.IO` | Dispatcher running the connection |
| `scope` | a new scope | Scope of the client's coroutines |

An engine can be shared by several clients (one WebSocket each, for example on different socket paths) and by the
application's HTTP requests. A client only closes its own connection: `close()` the engine once it is no longer
used, which closes every connection still open.

### HTTP requests to the same server

The engine also serves the application's HTTP requests to the same server, with the same certificate trust:

```kotlin
val response = engine.post("/api/v1/items", PhoenixHttpBody.json(item, Item.serializer()))
if (response.isSuccess) {
    val created = response.json(Item.serializer())
}

engine.get("/api/v1/items", query = listOf("ids[]" to "1", "ids[]" to "2"), timeout = 5000)
```

`request(PhoenixHttpRequest)` returns a `PhoenixHttpResponse` of any status, and throws `HttpRequestException` when
no response is received (network, TLS, timeout...). `get`, `post`, `put`, `patch` and `delete` are shortcuts.

- `path` is absolute and already percent-encoded (`"/items/a%20b"`): it is sent as is.
- `query` parameters are given raw and encoded by the engine; a name can be repeated.
- `timeout` covers the whole request, 10 s by default.

The application depends on the `PhoenixHttpEngine` interface, which exposes no Ktor type, and tests can implement it
to fake the server.

### Connection

```kotlin
client.connect(params = mapOf("token" to token), headers = mapOf("X-Custom" to "value"))

client.state.waitConnected()                      // suspends until connected
client.state.collect { println(it.connectionState) } // CONNECTING, CONNECTED, DISCONNECTED
```

Once connected, the client stays active and reconnects with `retryTimeout` after a network failure, a missed
heartbeat or any refused connection, except one: when the server refuses the connection with
**403** (`connect/3` returned `:error`), the client emits `Forbidden` on `client.messages` and becomes inactive.
Call `connect` again, for example with a new token:

```kotlin
client.messages.filter { it == Forbidden }.collect { /* refresh the token, then connect() */ }
```

`disconnect()` leaves every channel and closes the connection.

### Channels

```kotlin
val channel = client.join("room:42", Join(userId = 7)).getOrThrow()
```

- `join` waits for the connection, then sends `phx_join` until the server answers (retried after 1 s, 2 s, 5 s,
  then every 10 s). It fails with `ResponseException` if the server refuses the join (`{:error, ...}` in
  `join/3`), and with `BadActionException` if the client is not active (`connect` not called, or after
  `disconnect` or `Forbidden`).
- Joined channels are rejoined automatically after a reconnection, or when the server reports a crash
  (`phx_error`).
- Pushes made while the channel is rejoining wait for it to be joined, within their timeout.
- `channel.state` is a `Flow<ChannelState>`: `JOINING`, `JOINED`, `LEAVING`, `CLOSE`.
- `channel.leave()` sends `phx_leave` and stops rejoining the channel.

### Pushing

```kotlin
// With a reply
val reply: Result<Reply> = channel.push("hello", Hello(name = "toto"))
val reply2 = channel.push("hello", Hello(name = "toto"), timeout = 2_000L)

// Without waiting for a reply
channel.pushNoReply("typing", Typing(active = true))
```

Payloads can be `@Serializable` classes, maps or lists of serializable values, or a `JsonElement`
(`buildJsonObject { ... }`).

A push fails with:

| Exception | When |
|---|---|
| `ResponseException` | The server replied with `{:error, ...}`; `source` holds the reply |
| `TimeoutException` | No reply within the timeout |
| `ChannelException` | The channel was closed or crashed before the reply |
| `BadActionException` | The channel was never joined |

### Replies and incoming messages

```kotlin
val reply = channel.push("get_profile").getOrThrow()
reply.status                        // Status.OK, Status.ERROR
reply.convertTo<Profile>()          // decodes the "response" field
```

Messages pushed or broadcast by the server are available as flows:

```kotlin
channel.messages                    // messages of this channel's topic
    .filterEvent("new_msg")
    .collect { message ->
        val body = message.payload?.convertTo<NewMessage>()?.getOrNull()
    }

client.messages                     // every incoming message, including replies
```

`message.payload?.convertTo<T>("key")` decodes a single field of the payload, and `message.payload?.json` gives
the raw `JsonObject`. Unknown JSON keys are ignored.

### Logging

Logs go through [Kermit](https://kermit.touchlab.co) with tags starting with `PhoenixClient.`: Logcat on
Android, OSLog on iOS and the console on the JVM. They are configured through Kermit's global logger:

```kotlin
Logger.setMinSeverity(Severity.Warn)
```

### iOS

A `ws://` connection (`ssl = false`) or `untrustedCertificate = true` needs an
[App Transport Security](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity)
exception in the app's `Info.plist`.

### Custom engine

`phoenixClient(httpEngine, ...)` accepts any implementation of `PhoenixHttpEngine`, for example to run on another
transport or in tests. `connectWebSocket` returns the connection once it is open, or a failure: a
`WebSocketConnectException` with the status 403 makes the client inactive (reported as `Forbidden`), any other
failure is retried. Its receiver then gets the incoming messages, and `DISCONNECTED` once when the connection ends.

## Development

Requirements: JDK 25, Elixir and Erlang/OTP (for the test server), and Xcode for the iOS targets.

The integration tests run against the Phoenix server in [`test/`](test):

```sh
cd test
mix deps.get
mix phx.server          # listens on localhost:4000
```

Then, from the root:

```sh
./gradlew jvmTest                  # JVM tests
./gradlew iosSimulatorArm64Test    # iOS simulator tests
./gradlew build                    # everything, all targets
./gradlew koverHtmlReport          # coverage report (JVM)
```

CI runs the JVM tests on Linux and the iOS tests on macOS. Pushing a tag publishes the package to GitHub
Packages.

## License

[MIT](LICENSE.md)
