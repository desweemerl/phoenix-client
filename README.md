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
    val client = ktorPhoenixClient(host = "example.com").getOrThrow()

    // Connects in the background, and reconnects whenever the connection is lost.
    client.connect(params = mapOf("token" to "user1234"))

    // Waits for the connection, then joins.
    val channel = client.join("room:lobby").getOrThrow()

    val reply = channel.push("hello", Hello(name = "toto")).getOrThrow()
    println(reply.convertTo<HelloReply>().getOrThrow().message)

    client.disconnect()
}
```

On the Phoenix side, this matches a channel such as:

```elixir
def handle_in("hello", %{"name" => name}, socket) do
  {:reply, {:ok, %{message: "hello #{name}"}}, socket}
end
```

### Creating a client

`ktorPhoenixClient(...)` returns a `Result<Client>`. All parameters are optional:

| Parameter | Default | Description |
|---|---|---|
| `host` | `"localhost"` | Server host |
| `port` | `443` | Server port |
| `path` | `"/socket"` | Socket path, as in `socket "/socket", ...` in the endpoint (`/websocket` is appended) |
| `ssl` | `true` | `wss://` or `ws://` |
| `untrustedCertificate` | `false` | Accept any server certificate (development only) |
| `retryTimeout` | 10 ms, 50 ms … 2 s, then 5 s | Delay between reconnection attempts, by attempt number |
| `heartbeatInterval` | 30 s | Interval between heartbeats; must be ≥ `defaultTimeout` |
| `heartbeatTimeout` | 1 s | A heartbeat without reply within this delay closes the connection, which is then reopened |
| `defaultTimeout` | 10 s | Timeout of `push` and `leave` |
| `httpClient` | platform client | A custom Ktor `HttpClient`, which must install the `WebSockets` plugin |
| `dispatcher` | `Dispatchers.IO` | Dispatcher running the connection |
| `scope` | a new scope | Scope of the client's coroutines |

By default, the WebSocket runs on OkHttp on the JVM and on `NSURLSession` (Darwin) on iOS.

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

`phoenixClient(webSocketEngine, ...)` accepts any implementation of `WebSocketEngine`, for example to run on
another transport or in tests.

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
