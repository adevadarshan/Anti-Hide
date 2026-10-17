package com.antihide.v2.net

import com.antihide.v2.auth.InMemoryCsrfKeyProvider
import com.antihide.v2.auth.InMemorySessionStore
import com.antihide.v2.auth.SessionAuthManager
import com.antihide.v2.domain.AlarmState
import com.antihide.v2.domain.AntiHideStateMachine
import com.antihide.v2.domain.CommandRequest
import com.antihide.v2.domain.StopSource
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Slice 4: the state-synchronisation contract over a real loopback socket.
 *
 * Covers the polling fallback target, the named heartbeat event a browser can actually observe,
 * zombie-stream eviction on rebind/revoke, and the serverBootId that lets a client accept a
 * post-restart stateVersion of 1 instead of discarding it as stale (Security Invariant #9).
 */
@Timeout(60)
class StateSyncReconciliationTest {

    private class FileAssetSource(private val dir: File) : AssetSource {
        override fun read(name: String): ByteArray? {
            val file = File(dir, name)
            return if (file.isFile) file.readBytes() else null
        }
    }

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var auth: SessionAuthManager
    private lateinit var machine: AntiHideStateMachine
    private lateinit var server: TailscaleInterfaceServer
    private val openSockets = mutableListOf<Socket>()

    @BeforeEach
    fun setUp() {
        auth = SessionAuthManager(InMemorySessionStore(), InMemoryCsrfKeyProvider())
        server = newServer(heartbeatIntervalMs = 120)
        server.start()
        awaitBound()
    }

    @AfterEach
    fun tearDown() {
        openSockets.forEach { runCatching { it.close() } }
        openSockets.clear()
        if (::server.isInitialized) server.stop()
        serverScope.cancel()
    }

    /**
     * A restart builds a fresh state machine but keeps the session store, mirroring reality: the
     * process dies (so state and versioning reset) while paired sessions survive on disk.
     */
    private fun newServer(heartbeatIntervalMs: Long): TailscaleInterfaceServer {
        machine = AntiHideStateMachine(serverScope)
        return TailscaleInterfaceServer(
            auth = auth,
            stateMachine = machine,
            assets = FileAssetSource(File("src/main/assets/dashboard")),
            binderProvider = { LocatingSocketBinder(listOf(InetAddress.getByName("127.0.0.1"))) },
            port = 0,
            allowLoopbackForLocalTests = true,
            heartbeatIntervalMs = heartbeatIntervalMs,
        )
    }

    private fun awaitBound() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (server.boundPort() > 0) return
            if (server.state.value == TailscaleInterfaceServer.ServerState.ERROR) {
                error("server failed to bind: ${server.lastError.value}")
            }
            Thread.sleep(20)
        }
        error("server never bound")
    }

    /** These tests drive the machine synchronously from a plain JUnit thread. */
    private fun dispatch(command: CommandRequest) = runBlocking { machine.process(command) }

    // ---------------------------------------------------------------- helpers

    private fun httpGet(path: String, cookie: String?): Pair<Int, String> {
        val connection = URL("http", "127.0.0.1", server.boundPort(), path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            if (cookie != null) connection.setRequestProperty("Cookie", "antihide_sid=$cookie")
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    /** Pairs over loopback and returns the `antihide_sid` cookie value. */
    private fun pairAndGetCookie(): String {
        val code = server.openPairingWindow()
        val connection = URL("http", "127.0.0.1", server.boundPort(), "/api/v1/pair")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"code":"$code"}""".toByteArray()) }
            assertEquals(200, connection.responseCode)
            connection.getHeaderField("Set-Cookie")!!.substringBefore(';').substringAfter('=')
        } finally {
            connection.disconnect()
        }
    }

    private fun openEventStream(cookie: String): Socket {
        val socket = Socket("127.0.0.1", server.boundPort())
        socket.soTimeout = 5_000
        val request = buildString {
            append("GET /api/v1/events HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Cookie: antihide_sid=$cookie\r\n")
            append("Accept: text/event-stream\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        openSockets += socket
        return socket
    }

    /** Reads whatever has arrived within [timeoutMs], returning early once [marker] shows up. */
    private fun readUntil(socket: Socket, marker: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            val available = try {
                socket.getInputStream().available()
            } catch (_: Exception) {
                return text.toString()
            }
            if (available > 0) {
                val read = socket.getInputStream().read(buffer, 0, minOf(available, buffer.size))
                if (read < 0) return text.toString()
                text.append(String(buffer, 0, read))
                if (text.contains(marker)) return text.toString()
            } else {
                Thread.sleep(20)
            }
        }
        return text.toString()
    }

    /** True when the peer closed the connection, which is how a browser observes a dead stream. */
    private fun awaitEof(socket: Socket, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(1024)
        // A blocking read is required: a clean FIN leaves available() at 0, so polling available()
        // would never see the close.
        socket.soTimeout = 200
        while (System.currentTimeMillis() < deadline) {
            try {
                if (socket.getInputStream().read(buffer) < 0) return true
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- /api/v1/status

    @Test
    @DisplayName("GET /api/v1/status answers 401 without a session cookie")
    fun statusRequiresAuthentication() {
        val (status, _) = httpGet("/api/v1/status", cookie = null)
        assertEquals(401, status)

        val (bogus, _) = httpGet("/api/v1/status", cookie = "not-a-real-session")
        assertEquals(401, bogus, "a forged cookie must not pass Layer 2")
    }

    @Test
    @DisplayName("GET /api/v1/status returns the authoritative snapshot plus serverBootId")
    fun statusReturnsAuthoritativeSnapshot() {
        val cookie = pairAndGetCookie()
        dispatch(CommandRequest.Ring("status-1"))

        val (status, body) = httpGet("/api/v1/status", cookie)
        assertEquals(200, status)
        assertEquals("RINGING", Json.stringField(body, "alarmState"))
        assertNotNull(Json.stringField(body, "stateVersion"))
        assertEquals(server.serverBootId, Json.stringField(body, "serverBootId"))
        assertNotNull(Json.stringField(body, "serverTimeEpochMs"), "clients need the server clock for skew checks")
        assertEquals("ARMED", Json.stringField(body, "armState"))

        dispatch(CommandRequest.Stop("status-1-stop", StopSource.REMOTE))
        val (_, afterStop) = httpGet("/api/v1/status", cookie)
        assertEquals("IDLE", Json.stringField(afterStop, "alarmState"))
    }

    @Test
    @DisplayName("GET /api/v1/status never carries the CSRF token")
    fun statusDoesNotLeakCsrfToken() {
        val cookie = pairAndGetCookie()
        val (_, session) = httpGet("/api/v1/session", cookie)
        val (_, status) = httpGet("/api/v1/status", cookie)

        assertNotNull(Json.stringField(session, "csrfToken"))
        assertEquals(null, Json.stringField(status, "csrfToken"), "a 2s poll must not re-mint credentials")
        assertEquals(null, Json.stringField(status, "sessionCount"))
    }

    // ---------------------------------------------------------------- heartbeat

    @Test
    @DisplayName("SSE emits a named heartbeat event carrying stateVersion and serverBootId")
    fun sseEmitsNamedHeartbeatEvent() {
        val cookie = pairAndGetCookie()
        val socket = openEventStream(cookie)

        val seen = readUntil(socket, "event: heartbeat", 5_000)

        assertTrue(seen.contains("200 OK"), "stream must open as 200, got: ${seen.take(80)}")
        assertTrue(
            seen.contains("event: heartbeat"),
            "expected a named heartbeat event (browsers hide ': comment' lines), got: ${seen.take(200)}",
        )
        assertTrue(seen.contains(server.serverBootId), "heartbeat must carry serverBootId")
        assertNotNull(Json.stringField(seen, "stateVersion"), "heartbeat must carry stateVersion")
    }

    @Test
    @DisplayName("the first SSE frame is a full snapshot")
    fun sseOpensWithSnapshot() {
        val cookie = pairAndGetCookie()
        dispatch(CommandRequest.Ring("sse-1"))
        val socket = openEventStream(cookie)

        val seen = readUntil(socket, "\"alarmState\":\"RINGING\"", 5_000)

        assertTrue(seen.contains("data: {"), "expected an initial data frame, got: ${seen.take(200)}")
        assertTrue(seen.contains("RINGING"))
        assertTrue(seen.contains(server.serverBootId))
    }

    // ---------------------------------------------------------------- zombie eviction

    @Test
    @DisplayName("rebind() closes every open SSE stream instead of leaving a zombie socket")
    fun rebindEvictsStreams() {
        val cookie = pairAndGetCookie()
        val socket = openEventStream(cookie)
        readUntil(socket, "data: {", 5_000)
        assertEquals(1, server.sseSubscriberCount, "stream should be registered")

        server.rebind()

        assertEquals(0, server.sseSubscriberCount, "rebind must drop subscribers")
        assertTrue(awaitEof(socket, 3_000), "client must see EOF, not hang on a dead TCP tuple")
    }

    @Test
    @DisplayName("revokeAllSessions() terminates SSE streams and status then answers 401")
    fun revokeAllTerminatesStreamsAndStatus() {
        val cookie = pairAndGetCookie()
        val socket = openEventStream(cookie)
        readUntil(socket, "data: {", 5_000)
        assertEquals(1, server.sseSubscriberCount)

        val revoked = server.revokeAllSessions()

        assertEquals(1, revoked)
        assertEquals(0, server.sseSubscriberCount)
        assertTrue(awaitEof(socket, 3_000), "revocation must terminate the live stream")
        assertEquals(401, httpGet("/api/v1/status", cookie).first, "status must fail closed after revocation")
    }

    // ---------------------------------------------------------------- boot identity

    @Test
    @DisplayName("a restarted server reports a new serverBootId and a stateVersion back at 1")
    fun serverBootIdChangesAcrossRestart() {
        val firstBootId = server.serverBootId
        val cookie = pairAndGetCookie()
        repeat(6) { dispatch(CommandRequest.Ring("pre-restart-$it")); dispatch(CommandRequest.Stop("pre-restart-stop-$it", StopSource.REMOTE)) }
        assertTrue(machine.currentState.stateVersion > 1, "state must have advanced before the restart")

        server.stop()
        server = newServer(heartbeatIntervalMs = 120)
        server.start()
        awaitBound()

        assertNotEquals(firstBootId, server.serverBootId, "a new server instance needs a new boot id")
        assertEquals(1, machine.currentState.stateVersion, "a fresh state machine restarts at 1")
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState, "restart must never auto-ring (Invariant #2)")

        // A client holding the old boot id must still be able to authenticate and read state.
        val (_, body) = httpGet("/api/v1/status", cookie)
        assertEquals(server.serverBootId, Json.stringField(body, "serverBootId"))
        assertEquals("IDLE", Json.stringField(body, "alarmState"))
    }

    @Test
    @DisplayName("serverBootId is stable for the lifetime of one server instance")
    fun serverBootIdIsStableWithinOneInstance() {
        val cookie = pairAndGetCookie()
        val (_, first) = httpGet("/api/v1/status", cookie)
        dispatch(CommandRequest.Ring("stable-1"))
        val (_, second) = httpGet("/api/v1/status", cookie)
        assertEquals(Json.stringField(first, "serverBootId"), Json.stringField(second, "serverBootId"))
    }
}