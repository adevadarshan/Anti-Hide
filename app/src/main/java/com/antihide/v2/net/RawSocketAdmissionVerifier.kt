package com.antihide.v2.net

import java.net.InetAddress

/**
 * Layer 1 admission control.
 *
 * Operates exclusively on the raw `ByteArray` returned by [InetAddress.getAddress], which is a
 * pure memory read with zero name-service traffic. Reverse DNS lookups are deliberately absent
 * from this file: V1 turned numeric peer addresses into MagicDNS names mid-flight and every
 * numeric-IP admission filter then rejected legitimate reloads with 403.
 */
object RawSocketAdmissionVerifier {

    const val MIN_IPV4_BYTES = 4
    const val IPV6_BYTES = 16

    /** Verdict for a single accepted socket. */
    enum class Decision {
        /** Peer is a Tailscale IPv4 peer inside 100.64.0.0/10. */
        TAILSCALE_IPV4,

        /** Peer is a Tailscale IPv6 ULA peer inside fd7a:115c:a1e0::/48. */
        TAILSCALE_IPV6,

        /** Loopback peer, admitted only while a local test explicitly opted in. */
        LOOPBACK,

        /** Peer is on a local subnet (127.0.0.0/8 for IPv4, ::1 for IPv6). */
        NON_TAILSCALE_PEER,
    }

    data class Verdict(val decision: Decision, val allowed: Boolean) {
        val reason: String get() = decision.name
    }

    private val VERDICT_ALLOWED = Verdict(Decision.TAILSCALE_IPV4, true)
    private val VERDICT_IPV6 = Verdict(Decision.TAILSCALE_IPV6, true)
    private val VERDICT_LOOPBACK = Verdict(Decision.LOOPBACK, true)
    private val VERDICT_DENIED = Verdict(Decision.NON_TAILSCALE_PEER, false)

    /**
     * @param remoteBytes `socket.inetAddress.address`
     * @param allowLoopbackForLocalTests must be false in production service runs.
     */
    fun verify(
        remoteBytes: ByteArray,
        allowLoopbackForLocalTests: Boolean = false,
    ): Verdict = when {
        isTailscaleIpv4(remoteBytes) -> VERDICT_ALLOWED
        isTailscaleIpv6(remoteBytes) -> VERDICT_IPV6
        allowLoopbackForLocalTests && isLoopback(remoteBytes) -> VERDICT_LOOPBACK
        else -> VERDICT_DENIED
    }

    /**
     * Verifies both ends of the socket. `localBytes` is `socket.localAddress.address`; a wildcard
     * local address means the socket was not bound to a specific Tailscale address, which the
     * Linux Weak Host Model would happily accept traffic for.
     */
    fun verifySocket(
        remoteBytes: ByteArray,
        localBytes: ByteArray?,
        allowLoopbackForLocalTests: Boolean,
    ): Verdict {
        if (localBytes != null && isWildcard(localBytes)) return VERDICT_DENIED
        if (localBytes != null && isLoopback(localBytes) && !allowLoopbackForLocalTests) return VERDICT_DENIED
        return verify(remoteBytes, allowLoopbackForLocalTests)
    }

    /** Tailscale CGNAT IPv4 100.64.0.0/10. */
    fun isTailscaleIpv4(bytes: ByteArray?): Boolean =
        bytes != null &&
            bytes.size == MIN_IPV4_BYTES &&
            (bytes[0].toInt() and 0xFF) == 100 &&
            (bytes[1].toInt() and 0xC0) == 0x40

    /** Tailscale IPv6 ULA fd7a:115c:a1e0::/48. */
    fun isTailscaleIpv6(bytes: ByteArray?): Boolean =
        bytes != null &&
            bytes.size == IPV6_BYTES &&
            bytes[0] == 0xFD.toByte() &&
            bytes[1] == 0x7A.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0x5C.toByte() &&
            bytes[4] == 0xA1.toByte() &&
            bytes[5] == 0xE0.toByte()

    /** 127.0.0.0/8 or ::1. */
    fun isLoopback(bytes: ByteArray?): Boolean = when {
        bytes == null -> false
        bytes.size == MIN_IPV4_BYTES -> (bytes[0].toInt() and 0xFF) == 127
        bytes.size == IPV6_BYTES -> {
            var allZero = true
            for (i in 0 until 15) if (bytes[i] != 0.toByte()) allZero = false
            allZero && (bytes[15].toInt() and 0xFF) == 1
        }
        else -> false
    }

    /** 0.0.0.0 or :: — the Linux Wildcard Host Model default this project refuses to accept. */
    fun isWildcard(bytes: ByteArray?): Boolean = when {
        bytes == null -> false
        bytes.size == MIN_IPV4_BYTES ->
            (bytes[0].toInt() and 0xFF) == 0 && (bytes[1].toInt() and 0xFF) == 0 &&
                (bytes[2].toInt() and 0xFF) == 0 && (bytes[3].toInt() and 0xFF) == 0
        bytes.size == IPV6_BYTES -> bytes.all { it == 0.toByte() }
        else -> false
    }

    /**
     * Renders peer bytes for the sanitized diagnostic log. Invariant #8 forbids logging raw
     * client addresses, so the last two octets / the ULA tail are masked.
     */
    fun sanitize(bytes: ByteArray?): String = when {
        bytes == null -> "none"
        bytes.size == MIN_IPV4_BYTES -> "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.x.x"
        bytes.size == IPV6_BYTES -> buildString {
            append("fd7a:115c:a1e0:0:0:0:")
            append("x:x")
        }
        else -> "len${bytes.size}"
    }
}