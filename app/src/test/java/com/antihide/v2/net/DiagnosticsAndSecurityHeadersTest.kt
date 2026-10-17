package com.antihide.v2.net

import com.antihide.v2.auth.InMemoryCsrfKeyProvider
import com.antihide.v2.auth.InMemorySessionStore
import com.antihide.v2.auth.SessionAuthManager
import com.antihide.v2.domain.AntiHideStateMachine
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Slice 5: security headers on every response, and a diagnostics payload that carries no secrets.
 *
 * The header assertions deliberately walk a representative slice of the route table rather than one
 * endpoint, because [HttpResponse.writeHead] is the single choke point and a route that builds its
 * response differently is exactly what would slip through.
 */
@Timeout(60)
class DiagnosticsAndSecurityHeadersTest {

    private class FileAssetSource(private val dir: File) : AssetSource {
        override fun read(name: String): ByteArray? {
            val file = File(dir, name)
            return if (file.isFile) file.readBytes() else null
        }
    }

    private data class Headers(val status: Int, val map: Map<String, String>, val body: String) {
        fun header(name: String): String? = map[name.lowercase()]
    }

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var auth: SessionAuthManager
    private lateinit var server: TailscaleInterfaceServer

    @BeforeEach
    fun setUp() {
        auth = SessionAuthManager(InMemorySessionStore(), InMemoryCsrfKeyProvider())
        server = TailscaleInterfaceServer(
            auth = auth,
            stateMachine = AntiHideStateMachine(serverScope),
            assets = FileAssetSource(File("src/main/assets/dashboard")),
            binderProvider = { LocatingSocketBinder(listOf(InetAddress.getByName("127.0.0.1"))) },
            port = 0,
            allowLoopbackForLocalTests = true,
        )
        server.start()
        val deadline = System.currentTimeMillis() + 3_000
        while (server.boundPort() <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(server.boundPort() > 0, "server never bound")
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        serverScope.cancel()
    }

    /** Raw request so every response header survives; HttpURLConnection hides same-named duplicates. */
    private fun raw(method: String, path: String, cookie: String? = null, body: String? = null): Headers {
        val socket = java.net.Socket("127.0.0.1", server.boundPort())
        socket.soTimeout = 4_000
        try {
            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\n")
            sb.append("Host: 127.0.0.1\r\n")
            if (cookie != null) sb.append("Cookie: antihide_sid=$cookie\r\n")
            if (body != null) {
                sb.append("Content-Type: application/json\r\n")
                sb.append("Content-Length: ${body.toByteArray().size}\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            socket.getOutputStream().apply {
                write(sb.toString().toByteArray())
                if (body != null) write(body.toByteArray())
                flush()
            }
            val text = socket.getInputStream().bufferedReader().readText()
            val head = text.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val map = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return Headers(status, map, text.substringAfter("\r\n\r\n", ""))
        } finally {
            socket.close()
        }
    }

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

    // ---------------------------------------------------------------- security headers

    @Test
    @DisplayName("every response carries the full security header set")
    fun allRoutesCarrySecurityHeaders() {
        val cookie = pairAndGetCookie()
        val routes = listOf(
            "GET /" to null,
            "GET /app.js" to null,
            "GET /styles.css" to null,
            "GET /favicon.ico" to null,
            "GET /api/v1/ping" to null,
            "GET /api/v1/session" to cookie,
            "GET /api/v1/status" to cookie,
            "GET /api/v1/diagnostics" to cookie,
            "GET /api/v1/does-not-exist" to null,
        )
        routes.forEach { (route, jar) ->
            val (method, path) = route.split(' ')
            val h = raw(method, path, jar)
            assertNotNull(h.header("Content-Security-Policy"), "$route missing CSP")
            assertEquals(HttpResponse.CACHE_CONTROL, h.header("Cache-Control"), "$route missing no-store")
            assertEquals("nosniff", h.header("X-Content-Type-Options"), "$route missing nosniff")
            assertEquals("DENY", h.header("X-Frame-Options"), "$route missing DENY")
            assertEquals("no-referrer", h.header("Referrer-Policy"), "$route missing no-referrer")
            assertTrue(
                h.map.keys.none { it.startsWith("access-control-") },
                "$route leaked a CORS header: ${h.map.keys}",
            )
        }
    }

    @Test
    @DisplayName("the CSP forbids inline script, external origins, framing and form posts")
    fun cspIsStrict() {
        val h = raw("GET", "/")
        val csp = h.header("Content-Security-Policy")!!
        assertTrue(csp.contains("default-src 'self'"), csp)
        assertTrue(csp.contains("script-src 'self'"), csp)
        assertTrue(!csp.contains("script-src 'self' 'unsafe-inline'"), "inline script must stay forbidden: $csp")
        assertTrue(csp.contains("style-src 'self' 'unsafe-inline'"), csp)
        assertTrue(csp.contains("connect-src 'self'"), csp)
        assertTrue(csp.contains("frame-ancestors 'none'"), csp)
        assertTrue(csp.contains("base-uri 'none'"), csp)
        assertTrue(csp.contains("form-action 'none'"), csp)
    }

    @Test
    @DisplayName("the dashboard ships no inline script and no on* handler, so script-src 'self' holds")
    fun dashboardHasNoInlineScript() {
        val html = File("src/main/assets/dashboard/index.html").readText()
        assertFalse(html.contains("<script>"), "inline <script> block would be a CSP violation")
        assertFalse(Regex("\\son[a-z]+\\s*=").containsMatchIn(html), "inline on*= handler would be a CSP violation")
        assertTrue(html.contains("<script src=\"/app.js\"></script>"), "app.js must be loaded as an external script")
    }

    @Test
    @DisplayName("the dashboard pulls nothing from the network but this origin")
    fun dashboardHasNoExternalOrigins() {
        listOf("index.html", "app.js", "styles.css").forEach { name ->
            val text = File("src/main/assets/dashboard/$name").readText()
            assertFalse(text.contains("//cdn"), "$name references a CDN")
            assertFalse(Regex("https?://(?!127\\.0\\.0\\.1)").containsMatchIn(text), "$name references an external origin")
            assertFalse(text.contains("@import url(http"), "$name imports remote CSS")
        }
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    @DisplayName("GET /api/v1/diagnostics is 401 without a session or with a forged cookie")
    fun diagnosticsRequiresAuthentication() {
        assertEquals(401, raw("GET", "/api/v1/diagnostics").status)
        assertEquals(401, raw("GET", "/api/v1/diagnostics", cookie = "forged-session-value").status)
    }

    @Test
    @DisplayName("GET /api/v1/diagnostics returns the sanitized payload when authenticated")
    fun diagnosticsReturnsSanitizedPayload() {
        val cookie = pairAndGetCookie()
        val h = raw("GET", "/api/v1/diagnostics", cookie)
        assertEquals(200, h.status)

        assertNotNull(Json.stringField(h.body, "serverBootId"))
        assertEquals(TailscaleInterfaceServer.APP_VERSION, Json.stringField(h.body, "appVersion"))
        assertNotNull(Json.stringField(h.body, "stateVersion"))
        assertEquals("IDLE", Json.stringField(h.body, "alarmState"))
        assertNotNull(Json.stringField(h.body, "activeSessionSlots"))
        assertTrue(h.body.contains("boundAddresses"))
        assertTrue(h.body.contains("recentEvents"))
    }

    @Test
    @DisplayName("the diagnostics body leaks no session id, csrf token, pairing code or raw address")
    fun diagnosticsLeaksNoSecrets() {
        val cookie = pairAndGetCookie()
        val session = raw("GET", "/api/v1/session", cookie)
        val csrf = Json.stringField(session.body, "csrfToken")
        assertNotNull(csrf, "test needs a real token to search for")
        val token = csrf!!

        val body = raw("GET", "/api/v1/diagnostics", cookie).body

        assertFalse(body.contains(cookie), "diagnostics leaked the session id")
        assertFalse(body.contains(token), "diagnostics leaked the CSRF token")
        assertFalse(body.contains("antihide_sid=" + cookie), "diagnostics leaked a cookie header")
        assertFalse(body.contains("csrfToken"), "diagnostics exposed the csrfToken field")
        // No Tailscale CGNAT address may appear unmasked; loopback is the test peer.
        assertFalse(Regex("\\b100\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").containsMatchIn(body), "diagnostics leaked a raw Tailscale address")
        // Only a raw hex tail counts as a leak; the masked "fd7a:115c:a1e0::x" is the intended shape.
        assertFalse(Regex("fd7a:115c:a1e0::[0-9a-f]{1,4}").containsMatchIn(body), "diagnostics leaked a raw Tailscale v6 tail")
    }

    @Test
    @DisplayName("list fields are real JSON arrays, not quoted JSON blobs")
    fun listFieldsAreRealArrays() {
        // A quoted blob silently breaks every client that iterates the field, and stringField()
        // cannot tell the difference, so the raw body has to be asserted directly.
        val cookie = pairAndGetCookie()
        val body = raw("GET", "/api/v1/diagnostics", cookie).body
        assertTrue(body.contains("\"boundAddresses\":["), "boundAddresses must be an array: $body")
        assertTrue(body.contains("\"recentEvents\":["), "recentEvents must be an array: $body")
        assertFalse(body.contains("\"boundAddresses\":\""), "boundAddresses was serialised as a string")
        assertFalse(body.contains("\"recentEvents\":\""), "recentEvents was serialised as a string")
    }

    @Test
    @DisplayName("the event log scrubs a cookie value, a raw v4 address and the v6 interface id")
    fun netLogScrubsSecretsAtTheSink() {
        // The shape the server logs when it binds to tun0, built from synthetic fixtures so no real
        // device address is committed. 100.101.102.103 is the project's standing example CGNAT peer
        // (see verify-slice1.ps1 and IntegratedAuthFlowTest); ::1:2 is a placeholder interface id.
        // The `100.` prefix and the `fd7a:115c:a1e0::` ULA prefix are the load-bearing parts here:
        // NetLog only masks the CGNAT block and that ULA prefix, so a documentation-range address
        // such as 192.0.2.x would test nothing. Regression coverage is unchanged.
        NetLog.record("BOUND fd7a:115c:a1e0::1:2:8765,100.101.102.103:8765 probe antihide_sid=deadbeefcafe1234")
        val newest = NetLog.events.value.first()
        assertFalse(newest.contains("deadbeefcafe1234"), "NetLog leaked a session value: $newest")
        assertFalse(newest.contains("100.101.102.103"), "NetLog leaked a raw v4 address: $newest")
        assertFalse(newest.contains("::1:2"), "NetLog leaked the v6 interface id: $newest")
        assertTrue(newest.contains("100.101.x.x"), "v4 should be masked to shape: $newest")
        assertTrue(newest.contains("fd7a:115c:a1e0::x:x"), "v6 tail should be masked: $newest")
    }

    @Test
    @DisplayName("bound addresses are masked to the interface shape, keeping only the port literal")
    fun boundAddressesAreMasked() {
        val cookie = pairAndGetCookie()
        val body = raw("GET", "/api/v1/diagnostics", cookie).body
        val addresses = Json.stringField(body, "boundAddresses") ?: error("boundAddresses missing")
        assertTrue(addresses.contains("x.x") || addresses.contains("::x"), "host should be masked, got: $addresses")
        assertFalse(
            Regex("""\d+\.\d+\.\d+\.\d+:""").containsMatchIn(addresses),
            "a raw dotted-quad host leaked: $addresses",
        )
        assertFalse(
            Regex("""fd7a:[0-9a-f]{4}:[0-9a-f]{4}:[0-9a-f]{4}:""").containsMatchIn(addresses),
            "a raw v6 prefix leaked: $addresses",
        )
    }

    // ---------------------------------------------------------------- app version

    @Test
    @DisplayName("appVersion travels in session, status and diagnostics so a stale tab can detect a new APK")
    fun appVersionIsAdvertised() {
        val cookie = pairAndGetCookie()
        listOf("/api/v1/session", "/api/v1/status", "/api/v1/diagnostics").forEach { path ->
            val body = raw("GET", path, cookie).body
            assertEquals(TailscaleInterfaceServer.APP_VERSION, Json.stringField(body, "appVersion"), "$path missing appVersion")
        }
    }

    @Test
    @DisplayName("every state payload carries serverTimeEpochMs for skew-safe elapsed timing")
    fun statePayloadsCarryServerTime() {
        val cookie = pairAndGetCookie()
        listOf("/api/v1/session", "/api/v1/status").forEach { path ->
            val body = raw("GET", path, cookie).body
            assertNotNull(Json.stringField(body, "serverTimeEpochMs"), "$path missing serverTimeEpochMs")
        }
    }
}