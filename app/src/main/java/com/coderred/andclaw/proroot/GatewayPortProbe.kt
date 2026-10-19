package com.coderred.andclaw.proroot

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.InetAddress

internal enum class PortOccupancy { FREE, BUSY, UNKNOWN }

/** Bind only: no connection, HTTP request, or temporary listener is created. */
internal object GatewayPortProbe {
    private val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val ipv6Loopback = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })

    fun probe(port: Int): PortOccupancy {
        val ipv4 = probeAddress(OsConstants.AF_INET, ipv4Loopback, port)
        if (ipv4 == PortOccupancy.BUSY) return ipv4
        val ipv6 = probeAddress(OsConstants.AF_INET6, ipv6Loopback, port)
        return when {
            ipv6 == PortOccupancy.BUSY -> PortOccupancy.BUSY
            ipv4 == PortOccupancy.FREE && ipv6 != PortOccupancy.UNKNOWN -> PortOccupancy.FREE
            else -> PortOccupancy.UNKNOWN
        }
    }

    // null means the IPv6 family/loopback is explicitly unavailable, not a failed probe.
    private fun probeAddress(family: Int, address: InetAddress, port: Int): PortOccupancy? {
        var socket: FileDescriptor? = null
        var result: PortOccupancy? = PortOccupancy.UNKNOWN
        try {
            socket = Os.socket(family, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)
            // Ignore TIME_WAIT sockets, but never share an existing listening endpoint.
            Os.setsockoptInt(socket, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
            Os.bind(socket, address, port)
            result = PortOccupancy.FREE
        } catch (e: ErrnoException) {
            result = when {
                e.errno == OsConstants.EADDRINUSE -> PortOccupancy.BUSY
                family == OsConstants.AF_INET6 &&
                    (e.errno == OsConstants.EAFNOSUPPORT ||
                        e.errno == OsConstants.EPROTONOSUPPORT ||
                        e.errno == OsConstants.EADDRNOTAVAIL) -> null
                else -> PortOccupancy.UNKNOWN
            }
        } catch (_: Exception) {
            result = PortOccupancy.UNKNOWN
        } finally {
            if (socket != null) {
                try {
                    Os.close(socket)
                } catch (_: Exception) {
                    result = PortOccupancy.UNKNOWN
                }
            }
        }
        return result
    }
}
