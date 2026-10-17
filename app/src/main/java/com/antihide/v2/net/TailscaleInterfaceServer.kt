package com.antihide.v2.net

import com.antihide.v2.auth.CommandAuth
import com.antihide.v2.auth.PairResult
import com.antihide.v2.auth.SessionAuthManager
import com.antihide.v2.auth.SessionCheck
import com.antihide.v2.domain.AntiHideStateMachine
import com.antihide.v2.domain.CommandRequest
import com.antihide.v2.domain.StopSource
import com.antihide.v2.domain.SystemState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Supplies the PoC dashboard files. Android reads them from `assets/dashboard`. */
interface AssetSource {
    fun read(name: String): ByteArray?
}

/**
 * Slice 1 HTTP/1.1 + SSE server bound to the phone's Tailscale `tun0` addresses.
 *
 * Deliberately built on plain `java.net` sockets (roadmap Option A / Fallback A) so that every
 * accepted connection exposes `socket.inetAddress.address` and `socket.localAddress.address` as raw
 * byte arrays before a single HTTP byte is parsed.
 */
class TailscaleInterfaceServer(
    val auth: SessionAuthManager,
    val stateMachine: AntiHideStateMachine,
    private val assets: AssetSource,
    private val binderProvider: () -> SocketBinder?,
    val port: Int = DEFAULT_PORT,
    val allowLoopbackForLocalTests: Boolean = false,
    /** Shortened by unit tests so heartbeat behaviour is observable without a 15 second wait. */
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class ServerState { STOPPED, WAITING_FOR_VPN, BOUND, ERROR }

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _boundAddresses = MutableStateFlow<List<String>>(emptyList())
    val boundAddresses: StateFlow<List<String>> = _boundAddresses.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val stateVersion = AtomicLong(1)
    private val bootCount = AtomicLong(0)

    /**
     * Identity of *this server process*, unlike [bootCount] which counts dashboard page loads.
     * A client that sees a new value knows `stateVersion` restarted at 1 and must accept it instead
     * of discarding it as stale (Security Invariant #9).
     */
    val serverBootId: String = UUID.randomUUID().toString()

    private val sse = SseBroadcaster()
    private val assetCache = ConcurrentHashMap<String, ByteArray>()
    private val commandLock = Any()
    private val commandRing = LinkedHashSet<String>()
    private var broadcastJob: Job? = null

    private val lifecycleLock = Any()
    private var generation = 0
    private var running = false
    private var acceptJob: Job? = null

    @Volatile
    private var listeningSockets: List<ServerSocket> = emptyList()

    val sseSubscriberCount: Int get() = sse.subscriberCount

    fun start() = synchronized(lifecycleLock) {
        if (running) return
        running = true
        generation++
        acceptJob = scope.launch { acceptLoop(generation) }
        startStateBroadcast()
    }

    /**
     * Pushes every real state transition to SSE clients. Reads only — this path can never set
     * RINGING (Security Invariant #2).
     */
    private fun startStateBroadcast() {
        if (broadcastJob != null) return
        broadcastJob = scope.launch {
            stateMachine.state.collect {
                sse.broadcast("data: ${snapshotJson()}\n\n")
            }
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        running = false
        generation++
        acceptJob?.cancel()
        // ServerSocket.accept() is a blocking syscall: cancelling the coroutine does not unblock
        // it, so the listening sockets must be closed explicitly or the worker thread leaks.
        closeListeningSockets()
        sse.closeAll()
        _state.value = ServerState.STOPPED
    }

    /**
     * Drops every socket bound to the previous `tun0` instance and rebinds to the current one.
     * Called from the VPN `NetworkCallback` when Tailscale restarts or changes address.
     */
    fun rebind() {
        NetLog.record("REBIND requested")
        synchronized(lifecycleLock) {
            generation++
            acceptJob?.cancel()
            closeListeningSockets()
            if (running) acceptJob = scope.launch { acceptLoop(generation) }
        }
        // Every stream rides a TCP tuple that belongs to the old tun0 instance. Leaving them open
        // leaves the browser on a zombie socket that never fires onerror, so drop them and let the
        // client watchdog notice immediately.
        val closed = sse.closeAll()
        NetLog.record("REBIND sseClosed=$closed")
    }

    /** The port the kernel actually assigned, or -1 while the server is not listening. */
    fun boundPort(): Int = listeningSockets.firstOrNull()?.localPort ?: -1

    private fun closeListeningSockets() {
        val sockets = listeningSockets
        listeningSockets = emptyList()
        sockets.forEach { runCatching { it.close() } }
    }

    /**
     * Clears and closes [owned] only while it is still the live socket set. A superseded accept
     * loop must never close the sockets a newer generation just claimed.
     */
    private fun releaseIfOwned(owned: List<ServerSocket>) {
        synchronized(lifecycleLock) {
            if (listeningSockets === owned) {
                listeningSockets = emptyList()
                owned.forEach { runCatching { it.close() } }
            }
        }
    }

    fun revokeAllSessions(): Int {
        val closed = sse.closeAll()
        val cleared = auth.revokeAllSessions()
        NetLog.record("REVOKE sessions=$cleared sseClosed=$closed")
        return cleared
    }

    fun openPairingWindow(): String {
        val code = auth.openPairingWindow()
        NetLog.record("PAIRING_WINDOW_OPENED")
        return code
    }

    fun currentStateVersion(): Long = stateVersion.get()

    // ---------------------------------------------------------------- accept loop

    private suspend fun acceptLoop(myGeneration: Int) {
        // Binding happens under lifecycleLock so it is serialised with rebind()/stop(). Without this
        // a superseded loop keeps holding the port, the incoming loop's bind() fails, and nothing is
        // left accepting: the server goes Connection-refused after a rebind storm.
        val owned: List<ServerSocket>
        synchronized(lifecycleLock) {
            if (myGeneration != generation) return

            val binder = try {
                binderProvider()
            } catch (e: Exception) {
                NetLog.record("BINDER_ERROR ${e.javaClass.simpleName}")
                _state.value = ServerState.ERROR
                _lastError.value = "binder: ${e.javaClass.simpleName}"
                null
            }
            if (binder == null) {
                _state.value = ServerState.WAITING_FOR_VPN
                _boundAddresses.value = emptyList()
                return
            }

            val sockets = mutableListOf<ServerSocket>()
            try {
                for (address in binder.addresses()) {
                    try {
                        sockets += binder.open(port, address)
                    } catch (e: Exception) {
                        NetLog.record("BIND_FAIL ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                NetLog.record("BIND_ERROR ${e.javaClass.simpleName}")
            }

            if (sockets.isEmpty()) {
                _state.value = ServerState.WAITING_FOR_VPN
                _boundAddresses.value = emptyList()
                NetLog.record("WAITING_FOR_VPN (no Tailscale address)")
                return
            }

            owned = sockets.toList()
            listeningSockets = owned
            _state.value = ServerState.BOUND
            _lastError.value = null
            // Report the port the kernel actually assigned, which differs from `port` when it is 0.
            _boundAddresses.value = owned.map { "${it.inetAddress?.hostAddress}:${it.localPort}" }
            NetLog.record("BOUND ${_boundAddresses.value.joinToString(",")} loopbackAllowed=$allowLoopbackForLocalTests")
        }

        // One accept loop per listening socket. accept() blocks, so the sockets are closed by
        // stop()/rebind() to unblock them rather than relying on coroutine cancellation.
        try {
            coroutineScope {
                owned.forEach { serverSocket -> launch { acceptOn(serverSocket, myGeneration) } }
            }
        } finally {
            releaseIfOwned(owned)
        }
    }

    private suspend fun acceptOn(serverSocket: ServerSocket, myGeneration: Int) {
        while (running && myGeneration == generation && scope.isActive) {
            val accepted = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (myGeneration == generation && running) {
                    NetLog.record("ACCEPT_FAIL ${e.javaClass.simpleName}")
                }
                return
            }
            scope.launch { handleConnection(accepted) }
        }
    }

    /**
     * Layer 1 runs here: raw bytes are read from the socket object before any HTTP parsing, and a
     * rejected peer is dropped without a single byte of its request being interpreted.
     */
    private suspend fun handleConnection(socket: Socket) {
        val remoteBytes = try {
            socket.inetAddress?.address
        } catch (_: Exception) {
            null
        }
        val localBytes = try {
            socket.localAddress?.address
        } catch (_: Exception) {
            null
        }
        val verdict = RawSocketAdmissionVerifier.verifySocket(
            remoteBytes ?: ByteArray(0),
            localBytes,
            allowLoopbackForLocalTests,
        )
        if (!verdict.allowed) {
            NetLog.record("LAYER1_DENY reason=${verdict.reason} peer=${RawSocketAdmissionVerifier.sanitize(remoteBytes)}")
            runCatching { socket.close() }
            return
        }
        val peer = remoteBytes ?: ByteArray(0)

        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                sock.soTimeout = 15_000
                val request = HttpResponse.parse(BufferedInputStream(sock.getInputStream())) ?: run {
                    NetLog.record("BAD_REQUEST peer=${RawSocketAdmissionVerifier.sanitize(peer)}")
                    return
                }
                val response = route(request, peer)
                if (response.streaming) {
                    runStream(sock, response)
                } else {
                    HttpResponse.writeHead(BufferedOutputStream(sock.getOutputStream()), response)
                }
            } catch (e: Exception) {
                // Class name only: an exception message can quote request bytes, and this log is
                // served to browsers by /api/v1/diagnostics (Invariant #5).
                NetLog.record("HANDLER_ERROR ${e.javaClass.simpleName}")
                runCatching { sock.close() }
            }
        }
    }

    // ---------------------------------------------------------------- routing

    private suspend fun route(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val path = request.path
        val method = request.method
        return when {
            method == "GET" && path == "/" -> {
                bootCount.incrementAndGet()
                asset("index.html") ?: notFound()
            }
            method == "GET" && path == "/app.js" -> asset("app.js") ?: notFound()
            method == "GET" && path == "/styles.css" -> asset("styles.css") ?: notFound()
            method == "GET" && path == "/favicon.ico" -> HttpResponse(204, "image/x-icon", ByteArray(0))
            method == "GET" && path == "/api/v1/ping" -> {
                HttpResponse.json(200, Json.obj("status" to "ok"))
            }
            method == "POST" && path == "/api/v1/pair" -> handlePair(request, remoteBytes)
            method == "GET" && path == "/api/v1/session" -> handleSession(request, remoteBytes)
            method == "GET" && path == "/api/v1/status" -> handleStatus(request, remoteBytes)
            method == "GET" && path == "/api/v1/diagnostics" -> handleDiagnostics(request, remoteBytes)
            method == "POST" && path == "/api/v1/echo-command" -> handleEchoCommand(request, remoteBytes)
            method == "POST" && path == "/api/v1/command" -> handleCommand(request, remoteBytes)
            method == "GET" && path == "/api/v1/events" -> handleEvents(request, remoteBytes)
            path.startsWith("/api/") -> HttpResponse.json(404, Json.obj("error" to "NOT_FOUND"))
            else -> notFound()
        }
    }

    private fun handlePair(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val code = Json.stringField(String(request.body), "code")
            ?: return HttpResponse.json(400, Json.obj("authenticated" to false, "error" to "MALFORMED_BODY"))
        return when (val result = auth.submitPairingCode(code, remoteBytes)) {
            is PairResult.Success -> {
                NetLog.record("PAIR ok slots=${auth.activeSessionCount()} peer=${RawSocketAdmissionVerifier.sanitize(remoteBytes)}")
                HttpResponse.json(
                    200,
                    Json.obj(
                        "authenticated" to true,
                        "csrfToken" to result.session.csrfToken,
                        "stateVersion" to stateVersion.get(),
                        "sessionCount" to auth.activeSessionCount(),
                    ),
                    listOf(
                        "Set-Cookie" to
                            "${HttpResponse.COOKIE_NAME}=${result.session.sessionId}; HttpOnly; SameSite=Strict; Path=/",
                    ),
                )
            }
            is PairResult.WindowClosed -> {
                NetLog.record("PAIR reject=window_closed")
                HttpResponse.json(401, Json.obj("authenticated" to false, "error" to "PAIRING_WINDOW_CLOSED"))
            }
            is PairResult.BadCode -> {
                NetLog.record("PAIR reject=bad_code")
                HttpResponse.json(401, Json.obj("authenticated" to false, "error" to "INVALID_PAIRING_CODE"))
            }
            is PairResult.RateLimited -> {
                NetLog.record("PAIR reject=locked_out")
                HttpResponse.json(429, Json.obj("authenticated" to false, "error" to "PAIRING_LOCKED"))
            }
        }
    }

    private fun handleSession(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val sessionId = request.cookie(HttpResponse.COOKIE_NAME)
        return when (val check = auth.bootstrapSession(sessionId, remoteBytes)) {
            is SessionCheck.Authenticated -> jsonWithState(
                200,
                listOf(
                    "authenticated" to true,
                    "csrfToken" to check.session.csrfToken,
                    "bootCount" to bootCount.get(),
                    "sessionCount" to auth.activeSessionCount(),
                ),
                stateMachine.currentState,
            )
            is SessionCheck.Unauthorized -> {
                NetLog.record("SESSION reject peer=${RawSocketAdmissionVerifier.sanitize(remoteBytes)} cookie=${sessionId != null}")
                HttpResponse.json(401, Json.obj("authenticated" to false))
            }
        }
    }

    /**
     * Alarm fields shared by /api/v1/session, /api/v1/status, /api/v1/command and every SSE frame.
     * Returned as pairs so they are spread into each response as top-level keys rather than a nested
     * object. [TailscaleInterfaceServer.serverBootId] travels with them so a client can tell a fresh
     * server apart from a stale one.
     */
    private fun statePairs(state: SystemState): List<Pair<String, Any?>> = listOf(
        "alarmState" to state.alarmState.name,
        "armState" to state.armState.name,
        "volumePercent" to state.volumePercent,
        "stateVersion" to state.stateVersion,
        "ringStartedAtEpochMs" to state.ringStartedAtEpochMs,
        "lastStopSource" to state.lastStopSource?.name,
        "serverBootId" to serverBootId,
        // Lets a long-lived tab notice the APK was replaced and reload instead of running stale JS.
        "appVersion" to APP_VERSION,
        // Lets the dashboard compute the elapsed ring timer without trusting the laptop's clock.
        "serverTimeEpochMs" to System.currentTimeMillis(),
    )

    /**
     * GET /api/v1/status — the 2 second polling fallback target. Same Layer 1 + Layer 2 gate as
     * /api/v1/session, but deliberately omits the CSRF token and session bootstrap metadata so a
     * status poll cannot re-mint credentials (Security Invariant #5).
     */
    private fun handleStatus(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val sessionId = request.cookie(HttpResponse.COOKIE_NAME)
        return when (auth.bootstrapSession(sessionId, remoteBytes)) {
            is SessionCheck.Authenticated -> jsonWithState(
                200,
                listOf("ok" to true),
                stateMachine.currentState,
            )
            is SessionCheck.Unauthorized -> {
                NetLog.record("STATUS reject peer=${RawSocketAdmissionVerifier.sanitize(remoteBytes)} cookie=${sessionId != null}")
                HttpResponse.json(401, Json.obj("ok" to false, "error" to "UNAUTHORIZED"))
            }
        }
    }

    /**
     * GET /api/v1/diagnostics — the sanitized drawer payload (Invariants #5 and #8).
     *
     * Same Layer 1 + Layer 2 gate as every other authenticated route. Returns counts and masked
     * addresses only: never a session id, never the CSRF token, never a pairing code and never an
     * unmasked client address.
     */
    private fun handleDiagnostics(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val sessionId = request.cookie(HttpResponse.COOKIE_NAME)
        return when (auth.bootstrapSession(sessionId, remoteBytes)) {
            is SessionCheck.Authenticated -> {
                val state = stateMachine.currentState
                HttpResponse.json(
                    200,
                    Json.obj(
                        "ok" to true,
                        "serverBootId" to serverBootId,
                        "appVersion" to APP_VERSION,
                        "stateVersion" to state.stateVersion,
                        "alarmState" to state.alarmState.name,
                        "armState" to state.armState.name,
                        "volumePercent" to state.volumePercent,
                        "activeSseClients" to sse.subscriberCount,
                        "activeSessionSlots" to auth.activeSessionCount(),
                        "boundAddresses" to _boundAddresses.value.map(::maskAddress),
                        "recentEvents" to NetLog.events.value.take(NetLog.MAX_EVENTS),
                    ),
                )
            }
            is SessionCheck.Unauthorized -> {
                NetLog.record("DIAGNOSTICS reject peer=${RawSocketAdmissionVerifier.sanitize(remoteBytes)}")
                HttpResponse.json(401, Json.obj("ok" to false, "error" to "UNAUTHORIZED"))
            }
        }
    }

    /** Invariant #8: mask the host, keep the shape so a diagnostic reader can still reason about it. */
    private fun maskAddress(bound: String): String {
        val (host, port) = bound.substringBeforeLast(':') to bound.substringAfterLast(':')
        val masked = if (host.contains(':')) "fd7a:115c:a1e0::x" else host.split('.').take(2).joinToString(".") + ".x.x"
        return "$masked:$port"
    }

    private fun jsonWithState(status: Int, base: List<Pair<String, Any?>>, state: SystemState) =
        HttpResponse.json(status, Json.obj(*(base + statePairs(state)).toTypedArray()))

    /**
     * POST /api/v1/command — the real RING/STOP path. Layer 1 (tun0 + raw peer bytes) has already
     * run in [handleConnection]; Layer 2 cookie + boundPeerIp + X-AntiHide-CSRF runs here.
     */
    private suspend fun handleCommand(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val sessionId = request.cookie(HttpResponse.COOKIE_NAME)
        val csrfHeader = request.header(HttpResponse.CSRF_HEADER)
        return when (auth.authorizeCommand(sessionId, remoteBytes, csrfHeader)) {
            is CommandAuth.Unauthorized -> {
                NetLog.record("COMMAND reject=unauthorized")
                HttpResponse.json(401, Json.obj("ok" to false, "error" to "UNAUTHORIZED"))
            }
            is CommandAuth.InvalidCsrf -> {
                NetLog.record("COMMAND reject=csrf")
                HttpResponse.json(403, Json.obj("ok" to false, "error" to "INVALID_CSRF_TOKEN"))
            }
            is CommandAuth.Authorized -> {
                val body = String(request.body)
                val commandId = Json.stringField(body, "commandId")
                val action = Json.stringField(body, "action")?.uppercase()
                if (commandId == null || action == null) {
                    return jsonWithState(400, listOf("ok" to false, "error" to "MALFORMED_BODY"), stateMachine.currentState)
                }
                val command = when (action) {
                    "RING" -> CommandRequest.Ring(commandId)
                    "STOP" -> CommandRequest.Stop(commandId, StopSource.REMOTE)
                    else -> {
                        NetLog.record("COMMAND reject=unknown_action")
                        return jsonWithState(400, listOf("ok" to false, "error" to "UNKNOWN_ACTION"), stateMachine.currentState)
                    }
                }
                val result = stateMachine.process(command)
                if (result.rejected) {
                    NetLog.record("COMMAND reject=${result.error}")
                    return jsonWithState(400, listOf("ok" to false, "error" to result.error), result.state)
                }
                NetLog.record(
                    "COMMAND action=$action duplicate=${result.duplicate} " +
                        "alarm=${result.state.alarmState} stateVersion=${result.state.stateVersion}",
                )
                jsonWithState(200, listOf("ok" to true, "duplicate" to result.duplicate), result.state)
            }
        }
    }

    private fun handleEchoCommand(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val sessionId = request.cookie(HttpResponse.COOKIE_NAME)
        val csrfHeader = request.header(HttpResponse.CSRF_HEADER)
        return when (auth.authorizeCommand(sessionId, remoteBytes, csrfHeader)) {
            is CommandAuth.Unauthorized -> {
                NetLog.record("COMMAND reject=unauthorized")
                HttpResponse.json(401, Json.obj("authenticated" to false, "error" to "UNAUTHORIZED"))
            }
            is CommandAuth.InvalidCsrf -> {
                NetLog.record("COMMAND reject=csrf")
                HttpResponse.json(403, Json.obj("error" to "INVALID_CSRF_TOKEN"))
            }
            is CommandAuth.Authorized -> {
                val body = String(request.body)
                val commandId = Json.stringField(body, "commandId")
                    ?: return HttpResponse.json(400, Json.obj("error" to "MALFORMED_BODY"))
                val payload = Json.stringField(body, "payload") ?: ""
                val duplicate = rememberCommand(commandId)
                if (!duplicate) stateVersion.incrementAndGet()
                val snapshot = snapshotJson()
                sse.broadcast("data: $snapshot\n\n")
                NetLog.record("COMMAND ok duplicate=$duplicate stateVersion=${stateVersion.get()}")
                HttpResponse.json(
                    200,
                    Json.obj(
                        "authenticated" to true,
                        "commandId" to commandId,
                        "payload" to payload,
                        "duplicate" to duplicate,
                        "stateVersion" to stateVersion.get(),
                    ),
                )
            }
        }
    }

    private fun handleEvents(request: HttpRequest, remoteBytes: ByteArray): HttpResponse {
        val sessionId = request.cookie(HttpResponse.COOKIE_NAME)
        return when (auth.bootstrapSession(sessionId, remoteBytes)) {
            is SessionCheck.Authenticated -> HttpResponse(
                200,
                "text/event-stream",
                ByteArray(0),
                emptyList(),
                keepAlive = true,
                streaming = true,
            )
            is SessionCheck.Unauthorized -> {
                NetLog.record("EVENTS reject")
                HttpResponse.json(401, Json.obj("authenticated" to false))
            }
        }
    }

    /** 64-entry ring buffer; a commandId already in the ring is acknowledged without re-running. */
    private fun rememberCommand(commandId: String): Boolean = synchronized(commandLock) {
        val duplicate = !commandRing.add(commandId)
        while (commandRing.size > COMMAND_RING_SIZE) {
            val oldest = commandRing.first()
            commandRing.remove(oldest)
        }
        duplicate
    }

    private fun snapshotJson(): String = Json.obj(
        *(
            statePairs(stateMachine.currentState) + listOf(
                "bootCount" to bootCount.get(),
                "sessionCount" to auth.activeSessionCount(),
                "sseClients" to sse.subscriberCount,
            )
            ).toTypedArray(),
    )

    // ---------------------------------------------------------------- SSE

    private suspend fun runStream(socket: Socket, response: HttpResponse) {
        val out = BufferedOutputStream(socket.getOutputStream())
        HttpResponse.writeHead(out, response)
        val subscription = sse.subscribe(socket, "data: ${snapshotJson()}\n\n")
        val heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                subscription.queue.trySend(heartbeatFrame())
            }
        }
        try {
            for (frame in subscription.queue) {
                out.write(frame.toByteArray())
                out.flush()
            }
        } catch (e: IOException) {
            // Zombie or revoked client: drop it now instead of keeping a dead tuple registered.
            NetLog.record("SSE_WRITE_FAILED ${e.javaClass.simpleName}")
            runCatching { socket.close() }
        } catch (_: Exception) {
            // Browser navigated away or the stream was revoked.
        } finally {
            heartbeatJob.cancel()
            sse.close(subscription.id)
        }
    }

    /**
     * A named SSE event, not a `: comment` line: browsers deliberately do not surface comment
     * lines to `EventSource.onmessage`, so a comment-only heartbeat is invisible to the client
     * watchdog and a silent socket looks healthy forever.
     */
    private fun heartbeatFrame(): String {
        val state = stateMachine.currentState
        return "event: heartbeat\ndata: ${Json.obj(
            "stateVersion" to state.stateVersion,
            "serverBootId" to serverBootId,
        )}\n\n"
    }

    // ---------------------------------------------------------------- assets

    private fun asset(name: String): HttpResponse? {
        val bytes = assetCache.getOrPut(name) { assets.read(name) ?: ByteArray(0) }
        if (bytes.isEmpty()) return null
        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".js") -> "application/javascript; charset=utf-8"
            name.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }
        return HttpResponse(200, type, bytes)
    }

    private fun notFound() = HttpResponse(
        404,
        "text/plain; charset=utf-8",
        "404 Not Found\n".toByteArray(),
    )

    companion object {
        const val DEFAULT_PORT = 8765
        const val COMMAND_RING_SIZE = 64
        const val HEARTBEAT_INTERVAL_MS = 15_000L

        /** Bump whenever the shipped dashboard assets change, so open tabs reload themselves. */
        const val APP_VERSION = "v2.5"
    }
}