package com.mazdev.mazguard.dns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Upstream resolver. Sockets are protected with VpnService.protect() so their
 * traffic never re-enters our own tunnel.
 */
class DnsProxy(
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val upstreamProvider: () -> List<String>
) {

    fun resolve(payload: ByteArray): ByteArray? {
        for (server in upstreamProvider()) {
            val reply = query(server, payload, TIMEOUT_MS)
            if (reply != null) return reply
        }
        return null
    }

    fun resolveIps(host: String): List<ByteArray> {
        val id = COUNTER.incrementAndGet() and 0xFFFF
        val query = DnsCodec.buildQuery(id, host, DnsCodec.TYPE_A)
        val reply = resolve(query) ?: return emptyList()
        if (DnsCodec.readId(reply) != id) return emptyList()
        return DnsCodec.extractA(reply, reply.size)
    }

    private fun query(server: String, payload: ByteArray, timeoutMs: Int): ByteArray? {
        val socket = try {
            DatagramSocket()
        } catch (t: Throwable) {
            return null
        }
        try {
            if (!protectSocket(socket)) return null
            socket.soTimeout = timeoutMs
            val address = resolveLiteral(server) ?: return null
            socket.send(DatagramPacket(payload, payload.size, address, 53))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (packet.length < 12) return null
            if (buffer[0] != payload[0] || buffer[1] != payload[1]) return null
            return buffer.copyOf(packet.length)
        } catch (t: SocketTimeoutException) {
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun resolveLiteral(server: String): InetAddress? = try {
        // Only literal addresses are configured, so this never triggers a lookup.
        InetAddress.getByName(server)
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val TIMEOUT_MS = 1500
        private val COUNTER = AtomicInteger(1)
    }
}
