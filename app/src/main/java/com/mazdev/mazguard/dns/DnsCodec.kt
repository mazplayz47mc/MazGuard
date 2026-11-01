package com.mazdev.mazguard.dns

/**
 * Minimal DNS wire-format codec. Only what a filtering resolver needs:
 * read the question, build an answer (or an empty/negative answer), and pull
 * A records out of an upstream reply.
 *
 * Deliberately allocation-light and single-pass: this runs for every query.
 */
object DnsCodec {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1

    private const val FLAG_QR = 0x8000
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080

    data class Question(val name: String, val type: Int, val qclass: Int, val endOffset: Int)

    fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun writeU16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }

    fun readId(b: ByteArray): Int = if (b.size >= 2) readU16(b, 0) else -1

    fun parseQuestion(buf: ByteArray, len: Int): Question? {
        if (len < 12) return null
        if (readU16(buf, 4) < 1) return null
        var pos = 12
        val sb = StringBuilder(64)
        var hops = 0
        while (pos < len) {
            val labelLen = buf[pos].toInt() and 0xFF
            pos++
            if (labelLen == 0) break
            if ((labelLen and 0xC0) != 0) return null
            if (pos + labelLen > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            var i = 0
            while (i < labelLen) {
                val c = buf[pos + i].toInt() and 0xFF
                if (c < 0x21 || c > 0x7E) return null
                sb.append((c and 0x7F).toChar().lowercaseChar())
                i++
            }
            pos += labelLen
            if (++hops > 127) return null
        }
        if (pos + 4 > len) return null
        return Question(
            name = sb.toString(),
            type = readU16(buf, pos),
            qclass = readU16(buf, pos + 2),
            endOffset = pos + 4
        )
    }

    /** Answer with one A record per supplied address. Caller ensures q.type == TYPE_A. */
    fun buildAnswer(query: ByteArray, qEnd: Int, addresses: List<ByteArray>): ByteArray {
        val valid = addresses.filter { it.size == 4 }
        val out = ByteArray(qEnd + valid.size * 16)
        System.arraycopy(query, 0, out, 0, qEnd)
        writeHeader(out, anCount = valid.size, rcode = 0)
        var p = qEnd
        for (ip in valid) {
            out[p++] = 0xC0.toByte()
            out[p++] = 0x0C
            writeU16(out, p, TYPE_A); p += 2
            writeU16(out, p, CLASS_IN); p += 2
            // TTL: short so that changes take effect quickly, long enough to avoid churn.
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60
            writeU16(out, p, 4); p += 2
            System.arraycopy(ip, 0, out, p, 4)
            p += 4
        }
        return out
    }

    fun buildEmpty(query: ByteArray, qEnd: Int, rcode: Int): ByteArray {
        val out = ByteArray(qEnd)
        System.arraycopy(query, 0, out, 0, qEnd)
        writeHeader(out, anCount = 0, rcode = rcode)
        return out
    }

    private fun writeHeader(out: ByteArray, anCount: Int, rcode: Int) {
        val flags = FLAG_QR or FLAG_RD or FLAG_RA or (rcode and 0x0F)
        writeU16(out, 2, flags)
        writeU16(out, 4, 1)
        writeU16(out, 6, anCount)
        writeU16(out, 8, 0)
        writeU16(out, 10, 0)
    }

    fun buildQuery(id: Int, host: String, type: Int): ByteArray {
        val encoded = encodeName(host)
        val out = ByteArray(12 + encoded.size + 4)
        writeU16(out, 0, id)
        writeU16(out, 2, FLAG_RD)
        writeU16(out, 4, 1)
        writeU16(out, 6, 0)
        writeU16(out, 8, 0)
        writeU16(out, 10, 0)
        System.arraycopy(encoded, 0, out, 12, encoded.size)
        val p = 12 + encoded.size
        writeU16(out, p, type)
        writeU16(out, p + 2, CLASS_IN)
        return out
    }

    /** Extracts every A record address from a raw upstream reply. */
    fun extractA(buf: ByteArray, len: Int): List<ByteArray> {
        if (len < 12) return emptyList()
        val qdCount = readU16(buf, 4)
        val anCount = readU16(buf, 6)
        var pos = 12
        var i = 0
        while (i < qdCount) {
            pos = skipName(buf, pos, len)
            pos += 4
            i++
            if (pos > len) return emptyList()
        }
        val result = ArrayList<ByteArray>(anCount.coerceAtMost(8))
        i = 0
        while (i < anCount && pos + 10 <= len) {
            pos = skipName(buf, pos, len)
            if (pos + 10 > len) break
            val type = readU16(buf, pos)
            val rdLength = readU16(buf, pos + 8)
            pos += 10
            if (pos + rdLength > len) break
            if (type == TYPE_A && rdLength == 4) {
                result.add(buf.copyOfRange(pos, pos + 4))
            }
            pos += rdLength
            i++
        }
        return result
    }

    private fun skipName(buf: ByteArray, start: Int, len: Int): Int {
        var p = start
        while (p < len) {
            val labelLen = buf[p].toInt() and 0xFF
            if (labelLen == 0) return p + 1
            if ((labelLen and 0xC0) == 0xC0) return p + 2
            p += 1 + labelLen
        }
        return len
    }

    private fun encodeName(host: String): ByteArray {
        val parts = host.trim('.').split('.').filter { it.isNotEmpty() }
        var size = 1
        for (part in parts) size += part.length + 1
        val out = ByteArray(size)
        var p = 0
        for (part in parts) {
            out[p++] = part.length.toByte()
            for (ch in part) out[p++] = ch.code.toByte()
        }
        out[p] = 0
        return out
    }
}
