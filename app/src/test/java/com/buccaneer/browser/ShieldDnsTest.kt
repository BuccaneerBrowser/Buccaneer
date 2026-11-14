package com.buccaneer.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shield DNS codec + filter. Builds real wire-format packets so the
 * parse/serialize round-trip is exercised, not mocked.
 */
class ShieldDnsTest {

    // ------------------------------------------------------------- wire builders

    private fun nameWire(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun queryPacket(id: Int, name: String, qtype: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // standard query
        out.write(0); out.write(1)       // qdcount=1
        out.write(0); out.write(0)       // ancount=0
        out.write(0); out.write(0)       // nscount=0
        out.write(0); out.write(0)       // arcount=0
        out.write(nameWire(name))
        out.write(qtype shr 8 and 0xFF); out.write(qtype and 0xFF)
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    private fun recWire(type: Int, ttl: Long, rdata: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xC0); out.write(0x0C) // name pointer to question
        out.write(type shr 8 and 0xFF); out.write(type and 0xFF)
        out.write(0); out.write(1)
        out.write((ttl shr 24 and 0xFF).toInt()); out.write((ttl shr 16 and 0xFF).toInt())
        out.write((ttl shr 8 and 0xFF).toInt()); out.write((ttl and 0xFF).toInt())
        out.write(rdata.size shr 8 and 0xFF); out.write(rdata.size and 0xFF)
        out.write(rdata)
        return out.toByteArray()
    }

    private fun responsePacket(id: Int, qname: String, qtype: Int, recs: List<Pair<Int, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF); out.write(id and 0xFF)
        out.write(0x81); out.write(0x80) // response, RD, RA
        out.write(0); out.write(1)       // qdcount=1
        out.write(0); out.write(recs.size and 0xFF) // ancount
        out.write(0); out.write(0)       // nscount=0
        out.write(0); out.write(0)       // arcount=0
        out.write(nameWire(qname))
        out.write(qtype shr 8 and 0xFF); out.write(qtype and 0xFF)
        out.write(0); out.write(1)
        for ((t, r) in recs) out.write(recWire(t, 300, r))
        return out.toByteArray()
    }

    private fun ipv4(b1: Int, b2: Int, b3: Int, b4: Int) =
        byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte())

    private fun ipv6(a: Int, b: Int) =
        byteArrayOf((a shr 24).toByte(), (a shr 16).toByte(), (a shr 8).toByte(), a.toByte(),
            (b shr 24).toByte(), (b shr 16).toByte(), (b shr 8).toByte(), b.toByte())

    // ------------------------------------------------------------------- matcher

    @Test fun shieldMatchesExactAndSubdomains() {
        assertTrue(ShieldDns.isShielded("movy.sx"))
        assertTrue(ShieldDns.isShielded("www.movy.sx"))
        assertTrue(ShieldDns.isShielded("deep.www.movy.sx."))
        assertTrue(ShieldDns.isShielded("GHOSTFLIX.ST"))
        // streamcorner rotates apexes: main site + every mirror are in scope
        assertTrue(ShieldDns.isShielded("streamcorner.st"))
        assertTrue(ShieldDns.isShielded("streamcorner.xyz"))
        assertTrue(ShieldDns.isShielded("www.streamcorner.app"))
        assertTrue(ShieldDns.isShielded("sub.www.streamcorner.tv."))
        assertFalse(ShieldDns.isShielded("notmovy.sx"))
        assertFalse(ShieldDns.isShielded("evil-movy.sx"))
        assertFalse(ShieldDns.isShielded("evilstreamcorner.xyz"))
        assertFalse(ShieldDns.isShielded("notstreamcorner.st"))
        assertFalse(ShieldDns.isShielded("youtube.com"))
        assertFalse(ShieldDns.isShielded(""))
    }

    // --------------------------------------------------------------------- filter

    @Test fun shieldedWithAaaaReturnsAaaaOnly() {
        val pkt = responsePacket(0xABCD, "www.movy.sx", 1, listOf(
            1 to ipv4(104, 21, 36, 134),
            28 to ipv6(0x2606, 0x4700)
        ))
        val ans = ShieldDns.parseResponse(pkt)!!
        val out = ShieldDns.filter(ans)
        assertNotNull(out)
        val reparsed = ShieldDns.parseResponse(out!!)!!
        assertEquals("www.movy.sx", reparsed.qname)
        assertEquals(1, reparsed.answers.size)
        assertEquals(28, reparsed.answers[0].type)
    }

    @Test fun shieldedWithoutAaaaPassesThrough() {
        val pkt = responsePacket(0x1234, "ghostflix.st", 1, listOf(
            1 to ipv4(172, 67, 183, 206)
        ))
        val ans = ShieldDns.parseResponse(pkt)!!
        assertNull(ShieldDns.filter(ans))
    }

    @Test fun nonShieldedNeverTouched() {
        val pkt = responsePacket(0x5678, "www.youtube.com", 1, listOf(
            1 to ipv4(142, 250, 80, 47),
            28 to ipv6(0x2607, 0xf8b0)
        ))
        val ans = ShieldDns.parseResponse(pkt)!!
        assertNull(ShieldDns.filter(ans))
    }

    @Test fun queryParseRoundTrip() {
        val q = ShieldDns.parseQuery(queryPacket(0xBEEF, "www.MOVY.sx", 28))!!
        assertEquals("www.movy.sx", q.name)
        assertEquals(28, q.qtype)
        assertEquals(0xBEEF, q.id)
    }

    @Test fun malformedInputsReturnNull() {
        assertNull(ShieldDns.parseQuery(byteArrayOf(1, 2, 3)))
        assertNull(ShieldDns.parseResponse(byteArrayOf()))
    }
}
