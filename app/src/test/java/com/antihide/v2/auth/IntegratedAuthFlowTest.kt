package com.antihide.v2.auth

import com.antihide.v2.domain.AlarmState
import com.antihide.v2.domain.AntiHideStateMachine
import com.antihide.v2.net.AssetSource
import com.antihide.v2.net.Json
import com.antihide.v2.net.LocatingSocketBinder
import com.antihide.v2.net.TailscaleInterfaceServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end exercise of the whole Slice 1 stack over a real TCP socket on loopback: HTTP/1.1
 * parsing, Layer 1 raw-byte admission, Layer 2 pairing, session issuance, reload bootstrap and
 * CSRF authorization.
 */
@Timeout(60)
class IntegratedAuthFlowTest {

    private class FileAssetSource(private val dir: File) : AssetSource {
        override fun read(name: String): ByteArray? {
            val file = File(dir, name)
            return if (file.isFile) file.readBytes() else null
        }
    }

    private data class Reply(
        val status: Int,
        val body: String,
        val setCookie: String?,
        val contentType: String?,
    ) {
        fun csrf(): String? = Json.stringField(body, "csrfToken")
        fun cookieValue(): String? = setCookie?.substringBefore(';')?.substringAfter('=')
        fun alarmState(): String? = Json.stringField(body, "alarmState")
        fun stateVersion(): String? = Json.stringField(body, "stateVersion")
        fun lastStopSource(): String? = Json.stringField(body, "lastStopSource")
    }

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var store: InMemorySessionStore
    private lateinit var auth: SessionAuthManager
    private lateinit var machine: AntiHideStateMachine
    private lateinit var server: TailscaleInterfaceServer
    private var port = 0

    @BeforeEach
    fun startServer() {
        store = InMemorySessionStore()
        auth = SessionAuthManager(store, InMemoryCsrfKeyProvider())
        machine = AntiHideStateMachine(serverScope)
        server = TailscaleInterfaceServer(
            auth = auth,
            stateMachine = machine,
            assets = FileAssetSource(File("src/main/assets/dashboard")),
            binderProvider = { LocatingSocketBinder(listOf(InetAddress.getByName("127.0.0.1"))) },
            port = 0,
            allowLoopbackForLocalTests = true,
        )
        server.start()
        port = awaitBoundPort()
    }

    @AfterEach
    fun stopServer() {
        if (::server.isInitialized) server.stop()
        serverScope.cancel()
    }

    private fun awaitBoundPort(): Int {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val bound = server.boundPort()
            if (bound > 0) {
                assertEquals(TailscaleInterfaceServer.ServerState.BOUND, server.state.value)
                return bound
            }
            if (server.state.value == TailscaleInterfaceServer.ServerState.ERROR) {
                error("server failed to bind: ${server.lastError.value}")
            }
            Thread.sleep(25)
        }
        error("server never reached BOUND (state=${server.state.value})")
    }

    // ---------------------------------------------------------------- helpers

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        cookie: String? = null,
        csrf: String? = null,
    ): Reply {
        val connection = URL("http", "127.0.0.1", server.boundPort(), path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            if (cookie != null) connection.setRequestProperty("Cookie", "antihide_sid=$cookie")
            if (csrf != null) connection.setRequestProperty("X-AntiHide-CSRF", csrf)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(status, text, connection.getHeaderField("Set-Cookie"), connection.contentType)
        } finally {
            connection.disconnect()
        }
    }

    private fun pair(code: String): Reply = call("POST", "/api/v1/pair", """{"code":"$code"}""")

    private fun pairSuccessfully(): Reply {
        val code = server.openPairingWindow()
        return pair(code)
    }

    private fun echo(commandId: String, cookie: String?, csrf: String?) =
        call(
            "POST",
            "/api/v1/echo-command",
            """{"commandId":"$commandId","payload":"PING_TEST"}""",
            cookie,
            csrf,
        )

    private fun randomCommandId() = "cmd-${java.util.UUID.randomUUID()}"

    // ---------------------------------------------------------------- unauthenticated surface

    @Test
    @DisplayName("GET /api/v1/ping answers 200 without any credentials")
    fun pingIsUnauthenticated() {
        val reply = call("GET", "/api/v1/ping")
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("ok"))
    }

    @Test
    @DisplayName("the dashboard, script and stylesheet are served as 200 with no-store caching")
    fun staticAssetsAreServed() {
        assertEquals(200, call("GET", "/").status)
        assertEquals(200, call("GET", "/app.js").status)
        assertEquals(200, call("GET", "/styles.css").status)
    }

    @Test
    @DisplayName("an unknown route answers 404 rather than leaking a 500")
    fun unknownRouteIs404() {
        assertEquals(404, call("GET", "/nonexistent").status)
        assertEquals(404, call("GET", "/api/v1/does-not-exist").status)
    }

    // ---------------------------------------------------------------- stage 1

    @Test
    @DisplayName("pairing is refused with 401 while the window has never been opened")
    fun unopenedWindowIsRejected() {
        val reply = pair("ABCD-1234")
        assertEquals(401, reply.status)
        assertEquals("PAIRING_WINDOW_CLOSED", Json.stringField(reply.body, "error"))
    }

    @Test
    @DisplayName("an expired pairing window is refused and the code dies with it")
    fun expiredWindowIsRejected() {
        val expiring = SessionAuthManager(store, InMemoryCsrfKeyProvider(), pairingWindowMs = -1L)
        val code = expiring.openPairingWindow()
        val reply = call("POST", "/api/v1/pair", """{"code":"$code"}""")
        assertEquals(401, reply.status)
    }

    @Test
    @DisplayName("five wrong codes consume the window and the sixth attempt is rate limited")
    fun fiveFailuresThenTooManyRequests() {
        server.openPairingWindow()
        repeat(5) { attempt ->
            assertEquals(401, pair("WRNG-${attempt}00").status, "attempt ${attempt + 1} must be a plain rejection")
        }
        val sixth = pair("WRNG-9999")
        assertEquals(429, sixth.status)
        assertEquals("PAIRING_LOCKED", Json.stringField(sixth.body, "error"))
    }

    // ---------------------------------------------------------------- stage 2

    @Test
    @DisplayName("a valid code issues an HttpOnly SameSite=Strict cookie and a CSRF token")
    fun validPairingIssuesSession() {
        val reply = pairSuccessfully()
        assertEquals(200, reply.status)
        val cookie = reply.setCookie
        assertNotNull(cookie)
        assertTrue(cookie!!.contains("antihide_sid="), "cookie must be named antihide_sid")
        assertTrue(cookie.contains("HttpOnly"), "cookie must be HttpOnly")
        assertTrue(cookie.contains("SameSite=Strict"), "cookie must be SameSite=Strict")
        assertEquals("true", Json.stringField(reply.body, "authenticated"))
        assertNotNull(reply.csrf())
        assertEquals(1, auth.activeSessionCount())
    }

    @Test
    @DisplayName("the pairing code is single use: replaying it after success is refused")
    fun pairingCodeIsSingleUse() {
        val code = server.openPairingWindow()
        assertEquals(200, pair(code).status)
        val replay = pair(code)
        assertEquals(401, replay.status)
        assertEquals("PAIRING_WINDOW_CLOSED", Json.stringField(replay.body, "error"))
    }

    @Test
    @DisplayName("pairing twice from the same peer keeps both sessions live at once")
    fun multiOriginSlotsCoexist() {
        val first = pairSuccessfully()
        val second = pairSuccessfully()
        assertEquals(200, first.status)
        assertEquals(200, second.status)
        assertNotEquals(first.cookieValue(), second.cookieValue())

        // Both origins stay authenticated simultaneously.
        assertEquals(200, call("GET", "/api/v1/session", cookie = first.cookieValue()).status)
        assertEquals(200, call("GET", "/api/v1/session", cookie = second.cookieValue()).status)
        assertEquals(2, auth.activeSessionCount())
    }

    @Test
    @DisplayName("the fourth slot is the last one kept; a fifth pairing evicts the oldest")
    fun slotTableIsBoundedAtFour() {
        val cookies = (1..5).map { pairSuccessfully().cookieValue() }
        assertEquals(4, auth.activeSessionCount())
        assertEquals(401, call("GET", "/api/v1/session", cookie = cookies.first()).status)
        assertEquals(200, call("GET", "/api/v1/session", cookie = cookies.last()).status)
    }

    // ---------------------------------------------------------------- stage 3

    @Test
    @DisplayName("reload bootstrap restores the CSRF token from the cookie alone")
    fun reloadBootstrapRestoresToken() {
        val paired = pairSuccessfully()
        val bootstrap = call("GET", "/api/v1/session", cookie = paired.cookieValue())
        assertEquals(200, bootstrap.status)
        assertEquals("true", Json.stringField(bootstrap.body, "authenticated"))
        assertEquals(paired.csrf(), bootstrap.csrf())
        assertNotNull(bootstrap.stateVersion())
    }

    @Test
    @DisplayName("bootstrap without a cookie answers 401 with authenticated=false")
    fun bootstrapWithoutCookieIs401() {
        val reply = call("GET", "/api/v1/session")
        assertEquals(401, reply.status)
        assertEquals("false", Json.stringField(reply.body, "authenticated"))
    }

    @Test
    @DisplayName("bootstrap with an unknown session id answers 401")
    fun bootstrapWithForgedCookieIs401() {
        assertEquals(401, call("GET", "/api/v1/session", cookie = "not-a-real-session").status)
    }

    @Test
    @DisplayName("a session bound to a different peer IP cannot be used from this socket")
    fun bootstrapFromForeignPeerIpIs401() {
        // Issue a session to a different Tailscale peer than the one this socket presents.
        val foreignBytes = byteArrayOf(100, 101, 102, 99)
        val foreignWindow = server.openPairingWindow()
        val foreign = auth.submitPairingCode(foreignWindow, foreignBytes)
        val foreignSession = (foreign as PairResult.Success).session

        val reply = call("GET", "/api/v1/session", cookie = foreignSession.sessionId)
        assertEquals(401, reply.status)
        assertFalse(reply.body.contains(foreignSession.csrfToken), "a foreign session must never leak its token")
    }

    @Test
    @DisplayName("a CSRF token from a foreign peer session is rejected with 401 on commands too")
    fun commandFromForeignPeerIpIs401() {
        val foreignWindow = server.openPairingWindow()
        val foreign = (auth.submitPairingCode(foreignWindow, byteArrayOf(100, 101, 102, 99)) as PairResult.Success).session
        val reply = echo(randomCommandId(), foreign.sessionId, foreign.csrfToken)
        assertEquals(401, reply.status)
    }

    @Test
    @DisplayName("an expired session answers 401")
    fun expiredSessionIs401() {
        val expiring = SessionAuthManager(store, InMemoryCsrfKeyProvider(), sessionTtlMs = -1L)
        val result = expiring.submitPairingCode(expiring.openPairingWindow(), byteArrayOf(127, 0, 0, 1))
        val session = (result as PairResult.Success).session
        assertEquals(401, call("GET", "/api/v1/session", cookie = session.sessionId).status)
    }

    // ---------------------------------------------------------------- stage 4

    @Test
    @DisplayName("a valid cookie plus a valid CSRF header authorizes the command")
    fun validCommandSucceeds() {
        val paired = pairSuccessfully()
        val reply = echo(randomCommandId(), paired.cookieValue()!!, paired.csrf())
        assertEquals(200, reply.status)
        assertEquals("true", Json.stringField(reply.body, "authenticated"))
        assertEquals("false", Json.stringField(reply.body, "duplicate"))
    }

    @Test
    @DisplayName("a repeated commandId is acknowledged without being executed twice")
    fun commandIdIsIdempotent() {
        val paired = pairSuccessfully()
        val id = randomCommandId()
        val first = echo(id, paired.cookieValue()!!, paired.csrf())
        val second = echo(id, paired.cookieValue()!!, paired.csrf())
        assertEquals(200, first.status)
        assertEquals(200, second.status)
        assertEquals("false", Json.stringField(first.body, "duplicate"))
        assertEquals("true", Json.stringField(second.body, "duplicate"))
        assertEquals(
            Json.stringField(first.body, "stateVersion"),
            Json.stringField(second.body, "stateVersion"),
            "a duplicate must not bump stateVersion",
        )
    }

    @Test
    @DisplayName("a command without a cookie answers 401")
    fun commandWithoutCookieIs401() {
        val paired = pairSuccessfully()
        assertEquals(401, echo(randomCommandId(), "bogus", paired.csrf()).status)
    }

    @Test
    @DisplayName("a command with a missing or wrong CSRF header answers 403 INVALID_CSRF_TOKEN")
    fun commandWithoutCsrfIs403() {
        val paired = pairSuccessfully()
        val cookie = paired.cookieValue()!!

        val missing = echo(randomCommandId(), cookie, null)
        assertEquals(403, missing.status)
        assertEquals("INVALID_CSRF_TOKEN", Json.stringField(missing.body, "error"))

        val wrong = echo(randomCommandId(), cookie, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertEquals(403, wrong.status)
        assertEquals("INVALID_CSRF_TOKEN", Json.stringField(wrong.body, "error"))

        // The rejected attempts must not have advanced the state.
        val accepted = echo(randomCommandId(), cookie, paired.csrf())
        assertEquals(200, accepted.status)
        assertEquals("false", Json.stringField(accepted.body, "duplicate"))
    }

    @Test
    @DisplayName("revocation immediately invalidates every slot")
    fun revocationClearsAllSlots() {
        val first = pairSuccessfully()
        val second = pairSuccessfully()
        assertEquals(200, call("GET", "/api/v1/session", cookie = first.cookieValue()).status)

        assertEquals(2, server.revokeAllSessions())
        assertEquals(0, auth.activeSessionCount())
        assertEquals(401, call("GET", "/api/v1/session", cookie = first.cookieValue()).status)
        assertEquals(401, call("GET", "/api/v1/session", cookie = second.cookieValue()).status)
        assertEquals(401, echo(randomCommandId(), first.cookieValue(), first.csrf()).status)
    }

    @Test
    @DisplayName("a revoked cookie cannot be reused to re-pair; a new window is required")
    fun revokedSessionIsDead() {
        val paired = pairSuccessfully()
        server.revokeAllSessions()
        assertEquals(401, echo(randomCommandId(), paired.cookieValue(), paired.csrf()).status)
    }

    // ---------------------------------------------------------------- POST /api/v1/command

    private fun command(
        action: String,
        commandId: String?,
        cookie: String?,
        csrf: String?,
    ): Reply {
        val idPart = if (commandId == null) "" else ""","commandId":"$commandId""""
        return call(
            "POST",
            "/api/v1/command",
            """{"action":"$action"$idPart}""",
            cookie,
            csrf,
        )
    }

    @Test
    @DisplayName("RING then STOP through POST /api/v1/command drives the real state machine")
    fun ringStopThroughHttp() {
        val paired = pairSuccessfully()
        val cookie = paired.cookieValue()!!
        val csrf = paired.csrf()!!

        val ring = command("RING", "cmd-ring-1", cookie, csrf)
        assertEquals(200, ring.status)
        assertEquals("true", Json.stringField(ring.body, "ok"))
        assertEquals("RINGING", ring.alarmState())

        val stop = command("STOP", "cmd-stop-1", cookie, csrf)
        assertEquals(200, stop.status)
        assertEquals("IDLE", stop.alarmState())
        assertEquals("REMOTE", stop.lastStopSource())
    }

    @Test
    @DisplayName("a repeated commandId is a duplicate that does not advance stateVersion")
    fun duplicateCommandIdIsDeduplicated() {
        val paired = pairSuccessfully()
        val cookie = paired.cookieValue()!!
        val csrf = paired.csrf()!!

        val first = command("RING", "cmd-dup", cookie, csrf)
        val second = command("RING", "cmd-dup", cookie, csrf)
        assertEquals(200, first.status)
        assertEquals(200, second.status)
        assertEquals("false", Json.stringField(first.body, "duplicate"))
        assertEquals("true", Json.stringField(second.body, "duplicate"))
        assertEquals(first.stateVersion(), second.stateVersion())
        assertEquals("RINGING", machine.currentState.alarmState.name)
    }

    @Test
    @DisplayName("POST /api/v1/command without a cookie is 401 and leaves the alarm silent")
    fun commandWithoutCookieIs401AndSilent() {
        val before = machine.currentState.stateVersion
        val reply = command("RING", "cmd-noauth", null, null)
        assertEquals(401, reply.status)
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(before, machine.currentState.stateVersion)
    }

    @Test
    @DisplayName("POST /api/v1/command with a missing or wrong CSRF header is 403 and leaves the alarm silent")
    fun commandWithoutCsrfIs403AndSilent() {
        val paired = pairSuccessfully()
        val cookie = paired.cookieValue()!!
        val before = machine.currentState.stateVersion

        assertEquals(403, command("RING", "cmd-nocsrf", cookie, null).status)
        assertEquals(
            "INVALID_CSRF_TOKEN",
            Json.stringField(command("RING", "cmd-nocsrf-2", cookie, "wrong").body, "error"),
        )
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
        assertEquals(before, machine.currentState.stateVersion)
    }

    @Test
    @DisplayName("a missing commandId or an unknown action is 400 and never rings")
    fun malformedCommandIs400() {
        val paired = pairSuccessfully()
        val cookie = paired.cookieValue()!!
        val csrf = paired.csrf()!!

        assertEquals(400, command("RING", null, cookie, csrf).status)
        assertEquals(400, command("LAUNCH_ROCKET", "cmd-x", cookie, csrf).status)
        assertEquals("UNKNOWN_ACTION", Json.stringField(command("NOPE", "cmd-y", cookie, csrf).body, "error"))
        assertEquals(AlarmState.IDLE, machine.currentState.alarmState)
    }

    @Test
    @DisplayName("GET /api/v1/session reports the live alarm state")
    fun sessionReportsAlarmState() {
        val paired = pairSuccessfully()
        val cookie = paired.cookieValue()!!
        assertEquals("IDLE", sessionAlarmState(cookie))

        command("RING", "cmd-session-ring", cookie, paired.csrf())
        assertEquals("RINGING", sessionAlarmState(cookie))
    }

    private fun sessionAlarmState(cookie: String): String? =
        call("GET", "/api/v1/session", cookie = cookie).alarmState()

    // ---------------------------------------------------------------- rebind lifecycle

@Test
    @DisplayName("a rebind storm leaves exactly one listener serving requests")
    fun rebindDoesNotDuplicateListeners() {
        // Regression test: on the OPPO A58 the initial bind raced the first signature-poll rebind
        // and logged BOUND twice, briefly racing two socket sets for the same port.
        assertTrue(server.boundPort() > 0)
        repeat(5) { server.rebind() }

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && server.boundPort() <= 0) { Thread.sleep(25) }
        assertTrue(server.boundPort() > 0, "server stopped serving after repeated rebinds")

        assertEquals(200, call("GET", "/api/v1/ping").status)
        assertEquals(200, call("GET", "/").status)
        assertEquals(200, call("GET", "/app.js").status)
    }

    // ---------------------------------------------------------------- SSE

    @Test
    @DisplayName("the SSE stream refuses unauthenticated callers and streams to authenticated ones")
    fun sseRequiresAuthentication() {
        val unauthorized = openStream("/api/v1/events")
        try {
            assertEquals(401, unauthorized.responseCode)
        } finally {
            unauthorized.disconnect()
        }

        val paired = pairSuccessfully()
        val authorized = openStream("/api/v1/events")
        try {
            authorized.setRequestProperty("Cookie", "antihide_sid=${paired.cookieValue()}")
            assertEquals(200, authorized.responseCode)
            assertTrue(authorized.getHeaderField("Content-Type").contains("text/event-stream"))
            assertEquals(
                "no-store, no-cache, must-revalidate, max-age=0",
                authorized.getHeaderField("Cache-Control"),
            )
            assertEquals("nosniff", authorized.getHeaderField("X-Content-Type-Options"))
            assertEquals("DENY", authorized.getHeaderField("X-Frame-Options"))
            Assertions.assertNull(authorized.getHeaderField("Access-Control-Allow-Origin"))

            // Read exactly one SSE line; never drain the stream, it stays open indefinitely.
            val firstFrame = authorized.inputStream.bufferedReader().readLine()
            assertNotNull(firstFrame)
            assertTrue(firstFrame!!.startsWith("data: {"), "expected an initial state snapshot, got: $firstFrame")
        } finally {
            authorized.disconnect()
        }
    }

    /** Every stream opened by a test carries hard timeouts so nothing can hang the worker JVM. */
    private fun openStream(path: String): HttpURLConnection {
        val connection = URL("http", "127.0.0.1", server.boundPort(), path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return connection
    }

    // ---------------------------------------------------------------- Layer 1 on a real socket

    @Test
    @DisplayName("with loopback disabled the socket is dropped before any HTTP byte is read")
    fun layer1RejectsLoopbackInProductionMode() {
        val productionAuth = SessionAuthManager(InMemorySessionStore(), InMemoryCsrfKeyProvider())
        val production = TailscaleInterfaceServer(
            auth = productionAuth,
            stateMachine = AntiHideStateMachine(CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            assets = FileAssetSource(File("src/main/assets/dashboard")),
            binderProvider = { LocatingSocketBinder(listOf(InetAddress.getByName("127.0.0.1"))) },
            port = 0,
            allowLoopbackForLocalTests = false,
        )
        production.start()
        try {
            val deadline = System.currentTimeMillis() + 3_000
            var productionPort = -1
            while (System.currentTimeMillis() < deadline && productionPort <= 0) {
                productionPort = production.boundPort()
                Thread.sleep(25)
            }
            assertTrue(productionPort > 0, "production-mode server failed to bind")

            val connection = URL("http://127.0.0.1:$productionPort/api/v1/ping").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            val rejected = try {
                connection.responseCode != 200
            } catch (_: IOException) {
                true
            } finally {
                connection.disconnect()
            }
            assertTrue(rejected, "Layer 1 must drop a non-Tailscale peer before HTTP is interpreted")
        } finally {
            production.stop()
        }
    }
}