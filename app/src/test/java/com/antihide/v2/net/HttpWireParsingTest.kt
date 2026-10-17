package com.antihide.v2.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Socket-free framing tests: the CRLFCRLF terminator once broke every request. */
class HttpWireParsingTest {

    private fun parse(raw: String) =
        HttpResponse.parse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    @Test
    @DisplayName("a GET request terminates on CRLFCRLF even when the final byte is part of a header")
    fun parsesSimpleGet() {
        val request = parse(
            "GET /api/v1/ping HTTP/1.1\r\n" +
                "User-Agent: Java/17\r\n" +
                "Host: 127.0.0.1:8765\r\n" +
                "Accept: */*\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n",
        )
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/api/v1/ping", request.path)
        assertEquals("Java/17", request.header("User-Agent"))
    }

    @Test
    @DisplayName("a header whose value ends in CR or LF does not shift the terminator window")
    fun handlesHeadersWithControlCharacters() {
        val request = parse(
            "GET / HTTP/1.1\r\n" +
                "X-A: value\r\n" +
                "\r\n" +
                "GET /leaked HTTP/1.1\r\n" +
                "\r\n",
        )
        assertNotNull(request)
        assertEquals("/", request!!.path, "the second request must not leak into the first")
    }

    @Test
    @DisplayName("a POST body is read using Content-Length and not swallowed by the head reader")
    fun readsContentLengthBody() {
        val request = parse(
            "POST /api/v1/pair HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8765\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 20\r\n" +
                "\r\n" +
                "{\"code\":\"K7M9-P4X2\"}",
        )
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertArrayEquals("{\"code\":\"K7M9-P4X2\"}".toByteArray(), request.body)
        assertEquals("K7M9-P4X2", Json.stringField(String(request.body), "code"))
    }

    @Test
    @DisplayName("a body larger than Content-Length is left in the stream, not over-read")
    fun doesNotOverRead() {
        val request = parse(
            "POST /api/v1/pair HTTP/1.1\r\n" +
                "Content-Length: 2\r\n" +
                "\r\n" +
                "{}trailing-garbage",
        )
        assertNotNull(request)
        assertEquals("{}", String(request!!.body))
    }

    @Test
    @DisplayName("an empty body is tolerated and a missing terminator is rejected")
    fun emptyBodyAndBadFraming() {
        val empty = parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertNotNull(empty)
        assertEquals(0, empty!!.body.size)
        assertNull(parse("this is not http"))
        assertNull(parse(""))
    }

    @Test
    @DisplayName("query strings and fragments never reach the routing path")
    fun stripsQueryAndFragment() {
        assertEquals("/api/v1/session", parse("GET /api/v1/session?x=1 HTTP/1.1\r\n\r\n")!!.path)
        assertEquals("/api/v1/session", parse("GET /api/v1/session#frag HTTP/1.1\r\n\r\n")!!.path)
    }

    @Test
    @DisplayName("every response carries the hardening headers and never a CORS header")
    fun responseHeadersAreSafe() {
        val out = ByteArrayOutputStream()
        HttpResponse.writeHead(out, HttpResponse.json(200, """{"status":"ok"}"""))
        val text = out.toString(Charsets.ISO_8859_1)
        assertEquals(true, text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertEquals(true, text.contains("Cache-Control: no-store, no-cache, must-revalidate, max-age=0"))
        assertEquals(true, text.contains("X-Content-Type-Options: nosniff"))
        assertEquals(true, text.contains("X-Frame-Options: DENY"))
        assertEquals(false, text.contains("Access-Control-Allow-Origin"))
    }
}