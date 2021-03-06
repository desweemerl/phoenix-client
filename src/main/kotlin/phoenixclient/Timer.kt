package phoenixclient

import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import kotlin.system.measureTimeMillis

typealias DynamicTimeout = (tries: Int) -> Long?

fun Long.toDynamicTimeout(repeat: Boolean = false): DynamicTimeout = {
    if (repeat) {
        this
    } else {
        if (it > 0) null else this
    }
}

fun <T> timer(
    timeout: Long,
    block: suspend (timeout: Long) -> T
) = Timer(timeout.toDynamicTimeout(), block)

fun <T> timer(
    timeout: DynamicTimeout,
    block: suspend (timeout: Long) -> T
) = Timer(timeout, block)

suspend fun waitWhile(interval: Long, condition: () -> Boolean) {
    while (condition()) {
        delay(interval)
    }
}

suspend fun waitUntil(interval: Long, condition: () -> Boolean) =
    waitWhile(interval) { !condition() }

suspend fun waitWhile(interval: Long, duration: Long, condition: () -> Boolean) = coroutineScope{
    measureTimeMillis {  }
    val start = System.currentTimeMillis()
    waitWhile(interval) {
        condition() && ((System.currentTimeMillis() - start) <= duration)
    }
}

class Timer<T>(
    private val calcTimeout: DynamicTimeout,
    private val block: suspend (timeout: Long) -> T
) {
    private var tries = 0
    private var job: Job? = null

    private var _active = false
    val active: Boolean
        get() = _active

    private var _lastResult: Result<T>? = null
    val lastResult: Result<T>?
        get() = _lastResult

    suspend fun start() = coroutineScope {
        if (active) {
            throw BadActionException("Timer is already active")
        }

        job = launch {
            launchTimer()
        }
    }

    suspend fun reset() {
        job?.cancelAndJoin()
        tries = 0
        _lastResult = null
    }

    private suspend fun launchTimer() = coroutineScope {
        _lastResult = null

        _active = true

        while (_active) {
            val timeout = calcTimeout(tries++) ?: break

            val job = launch {
                _lastResult = try {
                    Result.success(block(timeout))
                } catch (ex: CancellationException) {
                    Result.failure(TimeoutException("Timer timed out after $timeout ms"))
                } catch (ex: Exception) {
                    _active = false
                    Result.failure(ex)
                }
            }

            waitWhile(1, timeout) {
                job.isActive
            }

            if (job.isActive) job.cancelAndJoin()
            if (lastResult?.isSuccess == true) _active = false
        }
    }
}