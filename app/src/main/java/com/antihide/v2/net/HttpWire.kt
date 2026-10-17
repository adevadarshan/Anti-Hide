package com.antihide.v2.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

const val MAX_HEADER_BYTES = 16 * 1024
const val MAX_BODY_BYTES = 64 * 1024

/** One parsed HTTP/1.1 request. Header lookup is case-insensitive by RFC 7230 rules. */
class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun cookie(name: String): String? =
        header("cookie")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val extraHeaders: List<Pair<String, String>> = emptyList(),
    /** SSE and other long-lived streams keep the socket open; everything else closes it. */
    val keepAlive: Boolean = false,
    /** Replaces the default status line for streams that write incrementally. */
    val streaming: Boolean = false,
) {
    companion object {
        const val CACHE_CONTROL = "no-store, no-cache, must-revalidate, max-age=0"
        const val COOKIE_NAME = "antihide_sid"
        const val CSRF_HEADER = "X-AntiHide-CSRF"

        /**
         * Applied by [writeHead] to every response, so no route can opt out. `'unsafe-inline'` is
         * limited to styles: the dashboard ships zero inline scripts and zero on* attributes, so
         * `script-src 'self'` holds without an exception. No CDN, font or image origin is reachable.
         */
        const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

        fun json(status: Int, body: String, extra: List<Pair<String, String>> = emptyList()) =
            HttpResponse(status, "application/json; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8), extra)

        fun text(status: Int, contentType: String, body: ByteArray) = HttpResponse(status, contentType, body)

        /**
         * Reads a request line, headers and (for POST) a Content-Length delimited body.
         * Returns null on malformed input or when a cap is exceeded; the caller closes the socket.
         */
        fun parse(input: InputStream): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            val requestLine = lines.first().split(' ')
            // The request line is never logged: a malformed target could carry a secret in a query string.
            if (requestLine.size < 3) return null
            val method = requestLine[0].uppercase()
            val target = requestLine[1]
            val path = target.substringBefore('?').substringBefore('#')

            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon <= 0) continue
                headers[lines[i].substring(0, colon).trim().lowercase()] =
                    lines[i].substring(colon + 1).trim()
            }

            var body = ByteArray(0)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > MAX_BODY_BYTES) return null
            if (contentLength > 0) body = ByteArray(contentLength).also { readFully(input, it) }

            return HttpRequest(method, path, headers, body)
        }

        private fun readHead(input: InputStream): ByteArray? {
            val buf = ByteArrayOutputStream(1024)
            // Rolling window of the last four bytes. Byte N lives in slot N % 4, so the oldest of
            // the last four (N-3) is at (N-3) % 4. Counting repeated LF would never fire either.
            val tail = ByteArray(4)
            var seen = 0
            while (buf.size() <= MAX_HEADER_BYTES) {
                val b = input.read()
                if (b < 0) {
                    // Byte count only: request bytes can carry the session cookie and must not be logged.
                    NetLog.record("HEAD_EOF after ${buf.size()} bytes")
                    return null
                }
                buf.write(b)
                seen++
                tail[seen % 4] = b.toByte()
                if (seen >= 4 &&
                    tail[(seen - 3) % 4] == 13.toByte() && tail[(seen - 2) % 4] == 10.toByte() &&
                    tail[(seen - 1) % 4] == 13.toByte() && tail[seen % 4] == 10.toByte()
                ) {
                    return buf.toByteArray()
                }
            }
            NetLog.record("HEAD_TOO_LARGE")
            return null
        }

        private fun readFully(input: InputStream, into: ByteArray) {
            var off = 0
            while (off < into.size) {
                val n = input.read(into, off, into.size - off)
                if (n < 0) return
                off += n
            }
        }

        /** Writes the status line and headers. Callers append the body themselves for SSE. */
        fun writeHead(out: OutputStream, response: HttpResponse, extra: List<Pair<String, String>> = emptyList()) {
            val sb = StringBuilder(512)
            sb.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            sb.append("Cache-Control: ").append(CACHE_CONTROL).append("\r\n")
            sb.append("Content-Security-Policy: ").append(CSP).append("\r\n")
            sb.append("X-Content-Type-Options: nosniff\r\n")
            sb.append("X-Frame-Options: DENY\r\n")
            sb.append("Referrer-Policy: no-referrer\r\n")
            sb.append("Content-Type: ").append(response.contentType).append("\r\n")
            // A streaming response has no known length; declaring one would end the body for the
            // client. 204 must not carry a body or a length either.
            if (!response.streaming && response.status != 204) {
                sb.append("Content-Length: ").append(response.body.size).append("\r\n")
            }
            sb.append("Connection: ").append(if (response.keepAlive) "keep-alive" else "close").append("\r\n")
            for ((k, v) in response.extraHeaders + extra) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            if (!response.streaming && response.status != 204) out.write(response.body)
            out.flush()
        }

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            429 -> "Too Many Requests"
            else -> "Error"
        }
    }
}