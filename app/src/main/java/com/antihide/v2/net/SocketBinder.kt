package com.antihide.v2.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.MessageDigest
import javax.net.ServerSocketFactory

/**
 * Creates the listening socket and declares which addresses this binder is willing to serve.
 * Production binds through the Android VPN `Network` socket factory so the kernel applies the
 * `tun0` fwmark; tests bind a plain socket on loopback.
 */
interface SocketBinder {
    /** Addresses this binder may legally bind. Empty means "no Tailscale interface yet". */
    fun addresses(): List<InetAddress>

    @Throws(IOException::class)
    fun open(port: Int, address: InetAddress): ServerSocket

    companion object {
        const val BACKLOG = 50
    }
}

/**
 * Production binder. Sockets are created unbound so `reuseAddress` is applied before `bind()`,
 * then bound to the address the VPN `Network` exposes. Creating the socket through
 * `Network.socketFactory` is what tags it to `tun0`.
 */
class VpnNetworkSocketBinder(private val network: android.net.Network) : SocketBinder {
    override fun addresses(): List<InetAddress> = TailscaleAddressLocator.fromSystem()

    // Android's Network.getSocketFactory() returns a SocketFactory whose runtime type really is a
    // ServerSocketFactory (that is how the socket gets tagged to tun0). Its declared type forbids
    // that downcast, so it is widened to Any first: the runtime behaviour is identical and the cast
    // stays legal. Do not "simplify" this away.
    override fun open(port: Int, address: InetAddress): ServerSocket {
        // The no-arg factory lives on ServerSocketFactory; creating the socket unbound is what
        // lets reuseAddress be set before bind().
        val raw: Any = network.socketFactory
        val factory = raw as? ServerSocketFactory
            ?: throw IOException("VPN socket factory does not support server sockets")
        val socket = factory.createServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, port), SocketBinder.BACKLOG)
        return socket
    }
}

/**
 * Finds the phone's Tailscale addresses and refuses to create a listening socket without one.
 *
 * Deliberately never falls back to a wildcard bind: under the Linux Weak Host Model a 0.0.0.0
 * listener would also answer packets that arrived over unencrypted Wi-Fi.
 */
class LocatingSocketBinder(private val candidateAddresses: List<InetAddress>) : SocketBinder {
    override fun addresses(): List<InetAddress> = candidateAddresses

    override fun open(port: Int, address: InetAddress): ServerSocket {
        val chosen = candidateAddresses.firstOrNull { sameAddress(it, address) } ?: candidateAddresses.firstOrNull()
        if (chosen == null) throw IOException("no Tailscale interface address available to bind")
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(chosen, port), SocketBinder.BACKLOG)
        return socket
    }

    private fun sameAddress(a: InetAddress, b: InetAddress): Boolean =
        a.address.size == b.address.size && MessageDigest.isEqual(a.address, b.address)
}

/**
 * Prefers the kernel-level VPN binding and degrades to a plain bind on the same `tun0` address.
 * Android does not guarantee that a non-VPN-owner app may tag its sockets to the VPN `Network`,
 * so the address-scoped bind stays available as the second line of defence.
 */
class FallbackSocketBinder(
    private val preferred: SocketBinder,
    private val fallback: SocketBinder,
) : SocketBinder {
    override fun addresses(): List<InetAddress> = preferred.addresses().ifEmpty { fallback.addresses() }

    override fun open(port: Int, address: InetAddress): ServerSocket =
        try {
            preferred.open(port, address)
        } catch (preferredFailure: Exception) {
            try {
                fallback.open(port, address)
            } catch (fallbackFailure: Exception) {
                throw IOException("vpn=${preferredFailure.javaClass.simpleName} local=${fallbackFailure.javaClass.simpleName}")
            }
        }
}

/** Picks the `tun0` addresses carrying this device's Tailscale IPv4 / IPv6. */
object TailscaleAddressLocator {
    const val TUN_INTERFACE_NAME = "tun0"

    fun fromSystem(): List<InetAddress> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces().toList()
        } catch (_: Exception) {
            emptyList()
        }
        return select(interfaces)
    }

    fun select(interfaces: List<NetworkInterface>): List<InetAddress> {
        fun tailscaleAddresses(iface: NetworkInterface): List<InetAddress> =
            iface.inetAddresses.toList()
                .filter { RawSocketAdmissionVerifier.isTailscaleIpv4(it.address) || RawSocketAdmissionVerifier.isTailscaleIpv6(it.address) }

        val tun = interfaces.filter { it.isUp && !it.isLoopback && it.name == TUN_INTERFACE_NAME }
        val fromTun = tun.flatMap { tailscaleAddresses(it) }
        if (fromTun.isNotEmpty()) return fromTun.distinctBy { it.address.joinToString(",") }

        // Some Android builds name the Tailscale tunnel differently; fall back to content matching.
        return interfaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { tailscaleAddresses(it) }
            .distinctBy { it.address.joinToString(",") }
    }
}