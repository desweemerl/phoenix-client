package phoenixclient

import kotlinx.coroutines.CoroutineDispatcher

// Dispatchers.IO is not declared in common code: it is a member on the JVM and an extension on native.
internal expect val ioDispatcher: CoroutineDispatcher
