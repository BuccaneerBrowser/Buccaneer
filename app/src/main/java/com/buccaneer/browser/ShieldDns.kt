package com.buccaneer.browser

/**
 * Minimal DNS wire-format codec + shield filter. Pure JVM — unit-testable without Android.
 *
 * Purpose: the bot-protection fleet walling the streaming aggregators tracks the home
 * IPv4. The same line's IPv6 identity is clean (verified 2026-11-13: full movy.sx page
 * served over IPv6 while IPv4 got the QR challenge). The shield answers DNS for the
 * fleet-fronted domains with AAAA-only when the upstream resolver returns any AAAA,
 * so the browser connects over IPv6 and the IPv4 ban stops applying.
 *
 * Fallback rule (never worse than status quo): if upstream returns no AAAA for a
 * shielded domain, the answer is passed through unchanged (IPv4 as before).
 * Non-shielded domains are always passed through untouched.
 */
object ShieldDns {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41

    /** Mirrors rotate apexes: every streamcorner.* host (main site + mirrors) is in scope. */
    const val STREAMCORNER_PREFIX = "streamcorner."

    /**
     * Fleet-fronted aggregators (the walled set). Player CDNs are NOT here.
     * Brand-scoped: mirrors rotate apexes, so each brand covers ANY TLD and subdomain
     * (same rule as STREAMCORNER_PREFIX above).
     */
    val SHIELD_BRANDS: List<String> = listOf(
        "ghostflix", "movy", "coreflix", "cinejoy", "moviebite",
        "flixer", "novahd", "reedstreams", "fmhy"
    )

    fun isShielded(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h.startsWith(STREAMCORNER_PREFIX) || h.contains(".$STREAMCORNER_PREFIX")) return true
        return SHIELD_BRANDS.any { PopupGuard.isBrandHost(h, it) }
    }

    // ---------------------------------------------------------------- query side

    class Query(val id: Int, val name: String, val qtype: Int)

    /** Parse a DNS query packet. Returns null on malformed input. */
    fun parseQuery(pkt: ByteArray): Query? {
        return try {
            if (pkt.size < 12) return null
            val id = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
            val qdcount = u16(pkt, 4)
            if (qdcount < 1) return null
            var pos = 12
            val name = readName(pkt, pos)?.let { n -> Pair(n.first, n.second) } ?: return null
            pos = name.second
            val qtype = u16(pkt, pos); pos += 2
            Query(id, name.first.lowercase(), qtype)
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- answer side

    class Rec(val type: Int, val ttl: Long, val rdata: ByteArray)

    class ParsedAns(
        val id: Int,
        val flags: Short,
        val qname: String,
        val rawQuestion: ByteArray,
        val answers: List<Rec>,
        val rcode: Int
    )

    /** Parse the answer section of a DNS response. Returns null on malformed input. */
    fun parseResponse(pkt: ByteArray): ParsedAns? {
        return try {
            if (pkt.size < 12) return null
            val id = ((pkt[0].toInt() and 0xFF) shl 8) or (pkt[1].toInt() and 0xFF)
            val flags = (((pkt[2].toInt() and 0xFF)) or ((pkt[3].toInt() and 0xFF) shl 8)).toShort()
            val qdcount = u16(pkt, 4)
            val ancount = u16(pkt, 6)
            if (qdcount < 1) return null
            var pos = 12
            val nm = readName(pkt, pos) ?: return null
            pos = nm.second
            pos += 4 // qtype + qclass
            val rawQuestion = pkt.copyOfRange(12, pos)
            val answers = ArrayList<Rec>()
            for (i in 0 until ancount) {
                if (pos >= pkt.size) return null
                val n = readName(pkt, pos) ?: return null
                pos = n.second
                if (pos + 10 > pkt.size) return null
                val type = u16(pkt, pos)
                val ttl = u32(pkt, pos + 4)
                val rdlen = u16(pkt, pos + 8)
                pos += 10
                if (pos + rdlen > pkt.size) return null
                answers.add(Rec(type, ttl, pkt.copyOfRange(pos, pos + rdlen)))
                pos += rdlen
            }
            val rcode = pkt[3].toInt() and 0x0F
            ParsedAns(id, flags, nm.first.lowercase(), rawQuestion, answers, rcode)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply the shield to a parsed response:
     *  - non-shielded name            -> null (caller passes the original packet through)
     *  - shielded, has >=1 AAAA       -> rebuilt packet with AAAA answers only
     *  - shielded, no AAAA            -> null (fallback: original packet, IPv4 as before)
     */
    fun filter(ans: ParsedAns): ByteArray? {
        if (!isShielded(ans.qname)) return null
        val aaaa = ans.answers.filter { it.type == TYPE_AAAA }
        if (aaaa.isEmpty()) return null
        return buildResponse(ans.id, (ans.flags.toInt() or 0x8000).toShort(), ans.rawQuestion, aaaa)
    }

    /** Serialize: header + raw question + records (plain-label names). */
    fun buildResponse(id: Int, flags: Short, rawQuestion: ByteArray, recs: List<Rec>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF); out.write(id and 0xFF)
        out.write(flags.toInt() shr 8 and 0xFF); out.write(flags.toInt() and 0xFF)
        out.write(u16be(1))                 // qdcount
        out.write(u16be(recs.size))         // ancount
        out.write(u16be(0))                 // nscount
        out.write(u16be(0))                 // arcount
        out.write(rawQuestion)
        for (r in recs) {
            out.write(0xC0); out.write(0x0C) // name pointer to question name
            out.write(u16be(r.type))
            out.write(u16be(1))             // class IN
            out.write(be32(r.ttl))
            out.write(u16be(r.rdata.size))
            out.write(r.rdata)
        }
        return out.toByteArray()
    }

    // ----------------------------------------------------------------- helpers

    private fun u16(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u16be(v: Int): ByteArray =
        byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun be32(v: Long): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    /** Decode a DNS name at pos. Returns (name, newPosAfterName) or null. Handles pointers. */
    fun readName(b: ByteArray, pos: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var p = pos
        var hops = 0
        var endPos = -1
        while (true) {
            if (p >= b.size || ++hops > 32) return null
            val len = b[p].toInt() and 0xFF
            if (len == 0) { p++; if (endPos < 0) endPos = p; break }
            if (len and 0xC0 == 0xC0) {
                // pointer
                if (p + 1 >= b.size) return null
                val target = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                if (endPos < 0) endPos = p + 2
                p = target
                continue
            }
            if (p + 1 + len > b.size) return null
            for (i in 0 until len) sb.append(b[p + 1 + i].toInt().toChar())
            if (sb.isNotEmpty()) sb.append('.')
            p += 1 + len
        }
        if (endPos < 0) endPos = p
        val name = sb.toString().trimEnd('.')
        return Pair(name, endPos)
    }

    private fun writeName(out: java.io.OutputStream, name: String) {
        // Answers reference the question name; a single 0x00 (root) is not valid for a
        // real qname, so emit the qname's compressed form: pointer 0xC00C -> question start.
        out.write(0xC0); out.write(0x0C)
    }
}
