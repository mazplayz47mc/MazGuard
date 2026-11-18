package com.mazdev.mazguard.dns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Upstream resolver with in-memory cache.
 * Sockets are protected with VpnService.protect() so their
 * traffic never re-enters our own tunnel.
 */
class DnsProxy(
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val upstreamProvider: () -> List<String>
) {

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val MAX_CACHE_ENTRIES = 2048

    fun resolve(payload: ByteArray): ByteArray? {
        val cacheKey = cacheKey(payload)
        val cached = cache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            hits.incrementAndGet()
            return cached.response
        }
        misses.incrementAndGet()

        for (server in upstreamProvider()) {
            val reply = query(server, payload, TIMEOUT_MS)
            if (reply != null) {
                cache.put(cacheKey, CacheEntry(reply))
                evictIfNeeded()
                return reply
            }
        }
        return null
    }

    fun resolveIps(host: String): List<ByteArray> {
        val cacheKey = "A:$host"
        val cached = cache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            hits.incrementAndGet()
            return DnsCodec.extractA(cached.response, cached.response.size)
        }
        misses.incrementAndGet()

        val id = COUNTER.incrementAndGet() and 0xFFFF
        val query = DnsCodec.buildQuery(id, host, DnsCodec.TYPE_A)
        val reply = resolve(query) ?: return emptyList()
        if (DnsCodec.readId(reply) != id) return emptyList()
        cache.put(cacheKey, CacheEntry(reply))
        evictIfNeeded()
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
        InetAddress.getByName(server)
    } catch (t: Throwable) {
        null
    }

    private fun cacheKey(payload: ByteArray): String {
        // Use question section as key (bytes 12 onwards) - convert to hex string
        val question = payload.copyOfRange(12, payload.size)
        return question.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun evictIfNeeded() {
        if (cache.size > MAX_CACHE_ENTRIES) {
            val toRemove = cache.entries
                .filter { it.value.isExpired() }
                .take(MAX_CACHE_ENTRIES / 4)
                .map { it.key }
            toRemove.forEach { cache.remove(it) }
        }
    }

    fun getStats(): Pair<Long, Long> = hits.get() to misses.get()

    private data class CacheEntry(
        val response: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > TTL_MS
    }

    companion object {
        private const val TIMEOUT_MS = 1500
        private const val TTL_MS = 30_000L
        private val COUNTER = AtomicInteger(1)
    }
}