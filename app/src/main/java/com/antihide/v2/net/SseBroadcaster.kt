package com.antihide.v2.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fan-out for Server-Sent Events. Each subscriber owns a socket and a small queue; a writer
 * coroutine drains the queue. Revocation closes the sockets directly so a client sees the stream
 * drop within one second (experiment E-07).
 */
class SseBroadcaster {

    private class Subscriber(
        val id: Long,
        val socket: java.net.Socket,
        val queue: Channel<String>,
    )

    /** Handle returned to the connection coroutine that owns the stream. */
    class Subscription(val id: Long, val queue: Channel<String>)

    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Subscriber>()
    private var nextId = 1L

    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    fun subscribe(socket: java.net.Socket, initialFrame: String): Subscription {
        val queue = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val id = synchronized(lock) {
            val sub = Subscriber(nextId++, socket, queue)
            subscribers[sub.id] = sub
            queue.trySend(initialFrame)
            sub.id
        }
        return Subscription(id, queue)
    }

    fun broadcast(frame: String) {
        synchronized(lock) { subscribers.values.toList() }
            .forEach { it.queue.trySend(frame) }
    }

    fun close(id: Long) {
        synchronized(lock) { subscribers.remove(id) }
    }

    /** Closes every live stream. Returns how many sockets were torn down. */
    fun closeAll(): Int {
        val all = synchronized(lock) {
            val copy = subscribers.values.toList()
            subscribers.clear()
            copy
        }
        all.forEach {
            it.queue.close()
            runCatching { it.socket.close() }
        }
        return all.size
    }
}

/** Sanitized event ring shown on the phone UI, mirrored to logcat and served by /api/v1/diagnostics. */
object NetLog {
    const val MAX_EVENTS = 20

    private val _events = MutableStateFlow<List<String>>(emptyList())

    /** Newest first. */
    val events: StateFlow<List<String>> = _events.asStateFlow()

    // Invariants #5 and #8: the log is shipped to the browser, so it is scrubbed at the sink rather
    // than trusted at each call site. A future caller that interpolates a token still cannot leak it.
    private val cookieValue = Regex("antihide_sid=[^\\s;]+", RegexOption.IGNORE_CASE)
    private val cgnatAddress = Regex("\\b(100)(\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b")
    // The tail of a Tailscale ULA is the device's own interface id, so it is masked too.
    private val tailscaleV6 = Regex("(fd7a:115c:a1e0::)[0-9a-f]{1,4}:[0-9a-f]{1,4}", RegexOption.IGNORE_CASE)

    fun record(message: String) {
        val safe = message
            .replace(cookieValue, "antihide_sid=<redacted>")
            .replace(cgnatAddress, "$1$2.x.x")
            .replace(tailscaleV6, "$1x:x")
        val line = "${System.currentTimeMillis()} $safe"
        _events.value = (listOf(line) + _events.value).take(MAX_EVENTS)
        try {
            android.util.Log.i("AntiHideNet", safe)
        } catch (_: Throwable) {
            // JVM unit tests run against a stubbed android.util.Log.
        }
    }
}