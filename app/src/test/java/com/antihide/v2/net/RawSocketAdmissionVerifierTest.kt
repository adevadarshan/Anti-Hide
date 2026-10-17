package com.antihide.v2.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Layer 1 boundary tests. Every case is expressed as a raw byte array, exactly as
 * `InetAddress.getAddress()` returns it, so no name service is involved at any point.
 */
class RawSocketAdmissionVerifierTest {

    private fun ipv4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(
        a.toByte(), b.toByte(), c.toByte(), d.toByte(),
    )

    private fun ipv6(vararg prefix: Int) =
        ByteArray(16).also { bytes -> prefix.forEachIndexed { i, v -> bytes[i] = v.toByte() } }

    private val tailscaleV6Prefix = ipv6(0xFD, 0x7A, 0x11, 0x5C, 0xA1, 0xE0)

    // ---------------------------------------------------------------- 100.64.0.0/10

    @Test
    @DisplayName("100.63.255.255 sits just below the CGNAT block and is rejected")
    fun belowCgnatBlock() {
        assertFalse(RawSocketAdmissionVerifier.verify(ipv4(100, 63, 255, 255)).allowed)
    }

    @Test
    @DisplayName("100.64.0.0 is the first address of the CGNAT block and is accepted")
    fun firstCgnatAddress() {
        val verdict = RawSocketAdmissionVerifier.verify(ipv4(100, 64, 0, 0))
        assertTrue(verdict.allowed)
        assertEquals(RawSocketAdmissionVerifier.Decision.TAILSCALE_IPV4, verdict.decision)
    }

    @Test
    @DisplayName("100.100.100.100 is the MagicDNS responder and is accepted")
    fun magicDnsResponder() {
        assertTrue(RawSocketAdmissionVerifier.verify(ipv4(100, 100, 100, 100)).allowed)
    }

    @Test
    @DisplayName("100.127.255.255 is the last address of the CGNAT block and is accepted")
    fun lastCgnatAddress() {
        val verdict = RawSocketAdmissionVerifier.verify(ipv4(100, 127, 255, 255))
        assertTrue(verdict.allowed)
        assertEquals(RawSocketAdmissionVerifier.Decision.TAILSCALE_IPV4, verdict.decision)
    }

    @Test
    @DisplayName("100.128.0.0 starts the next /16 and is rejected")
    fun aboveCgnatBlock() {
        assertFalse(RawSocketAdmissionVerifier.verify(ipv4(100, 128, 0, 0)).allowed)
    }

    @Test
    @DisplayName("a private LAN address on 192.168.1.50 is rejected")
    fun lanAddress() {
        assertFalse(RawSocketAdmissionVerifier.verify(ipv4(192, 168, 1, 50)).allowed)
    }

    @Test
    @DisplayName("the CGNAT mask really is /10 and not /8 or /16")
    fun maskIsExactlySlashTen() {
        assertTrue(RawSocketAdmissionVerifier.isTailscaleIpv4(ipv4(100, 64, 0, 0)))
        assertTrue(RawSocketAdmissionVerifier.isTailscaleIpv4(ipv4(100, 127, 255, 255)))
        assertFalse(RawSocketAdmissionVerifier.isTailscaleIpv4(ipv4(100, 63, 255, 255)))
        assertFalse(RawSocketAdmissionVerifier.isTailscaleIpv4(ipv4(100, 128, 0, 0)))
        assertFalse(RawSocketAdmissionVerifier.isTailscaleIpv4(ipv4(101, 0, 0, 0)))
    }

    // ---------------------------------------------------------------- fd7a:115c:a1e0::/48

    @Test
    @DisplayName("fd7a:115c:a1e0::1 is inside the Tailscale ULA and is accepted")
    fun tailscaleUlaFirstAddress() {
        val bytes = tailscaleV6Prefix.copyOf().also { it[15] = 1 }
        val verdict = RawSocketAdmissionVerifier.verify(bytes)
        assertTrue(verdict.allowed)
        assertEquals(RawSocketAdmissionVerifier.Decision.TAILSCALE_IPV6, verdict.decision)
    }

    @Test
    @DisplayName("fd7a:115c:a1e1::1 falls outside the /48 prefix and is rejected")
    fun tailscaleUlaNeighbourPrefix() {
        val bytes = ipv6(0xFD, 0x7A, 0x11, 0x5C, 0xA1, 0xE1).also { it[15] = 1 }
        assertFalse(RawSocketAdmissionVerifier.verify(bytes).allowed)
    }

    @Test
    @DisplayName("a plain IPv6 ULA outside fd7a:115c:a1e0::/48 is rejected")
    fun otherUla() {
        val bytes = ipv6(0xFD, 0x00, 0x11, 0x5C, 0xA1, 0xE0).also { it[15] = 1 }
        assertFalse(RawSocketAdmissionVerifier.verify(bytes).allowed)
    }

    // ---------------------------------------------------------------- loopback gating

    @Test
    @DisplayName("loopback is denied by default and allowed only under the explicit test flag")
    fun loopbackIsOptIn() {
        assertFalse(RawSocketAdmissionVerifier.verify(ipv4(127, 0, 0, 1)).allowed)
        val allowed = RawSocketAdmissionVerifier.verify(ipv4(127, 0, 0, 1), true)
        assertTrue(allowed.allowed)
        assertEquals(RawSocketAdmissionVerifier.Decision.LOOPBACK, allowed.decision)
    }

    @Test
    @DisplayName("::1 is gated by the same flag as 127.0.0.1")
    fun ipv6LoopbackIsOptIn() {
        val bytes = ByteArray(16).also { it[15] = 1 }
        assertFalse(RawSocketAdmissionVerifier.verify(bytes).allowed)
        assertTrue(RawSocketAdmissionVerifier.verify(bytes, true).allowed)
    }

    // ---------------------------------------------------------------- Weak Host Model defence

    @Test
    @DisplayName("a wildcard local address is never accepted, even for a Tailscale peer")
    fun wildcardLocalAddressIsRejected() {
        val remote = ipv4(100, 64, 0, 5)
        val verdict = RawSocketAdmissionVerifier.verifySocket(remote, ipv4(0, 0, 0, 0), false)
        assertFalse(verdict.allowed)
    }

    @Test
    @DisplayName("a non-Tailscale peer on a loopback-bound socket is still denied")
    fun loopbackLocalWithLanPeerDenied() {
        val verdict = RawSocketAdmissionVerifier.verifySocket(ipv4(192, 168, 1, 50), ipv4(127, 0, 0, 1), true)
        assertFalse(verdict.allowed)
    }

    @Test
    @DisplayName("a Tailscale peer on an explicitly bound Tailscale local address is admitted")
    fun tailscaleLocalWithTailscalePeerAdmitted() {
        val verdict = RawSocketAdmissionVerifier.verifySocket(ipv4(100, 101, 102, 103), ipv4(100, 64, 0, 7), false)
        assertTrue(verdict.allowed)
    }

    // ---------------------------------------------------------------- input hygiene

    @Test
    @DisplayName("empty, truncated and null byte arrays are denied rather than throwing")
    fun malformedInputIsDenied() {
        assertFalse(RawSocketAdmissionVerifier.isTailscaleIpv4(null))
        assertFalse(RawSocketAdmissionVerifier.isTailscaleIpv6(null))
        assertFalse(RawSocketAdmissionVerifier.isLoopback(null))
        assertFalse(RawSocketAdmissionVerifier.verify(ByteArray(0)).allowed)
        assertFalse(RawSocketAdmissionVerifier.verify(byteArrayOf(100, 64, 0)).allowed)
        assertFalse(RawSocketAdmissionVerifier.verify(byteArrayOf(0xFD.toByte(), 0x7A)).allowed)
    }

    @Test
    @DisplayName("sanitized rendering masks the identifying half of an address")
    fun sanitizeMasksHostBits() {
        assertEquals("100.64.x.x", RawSocketAdmissionVerifier.sanitize(ipv4(100, 64, 3, 9)))
        assertEquals("none", RawSocketAdmissionVerifier.sanitize(null))
    }
}