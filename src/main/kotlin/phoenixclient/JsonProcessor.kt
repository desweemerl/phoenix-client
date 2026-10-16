package phoenixclient

import kotlinx.serialization.json.Json

val JsonProcessor: Json = Json {
    // A payload class only needs to declare the fields it uses.
    ignoreUnknownKeys = true
    // Like Gson: properties with default values are sent, null properties are not, and missing
    // nullable properties are decoded as null.
    encodeDefaults = true
    explicitNulls = false
}
