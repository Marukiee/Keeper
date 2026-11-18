package nl.markmaaktmedia.keeper.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HDR behouden: de onderdelen van een Ultra HDR-bestand, getoetst tegen de bytes van een echte Oppo-foto (zie de constanten onderaan). */
class UltraHdrWriterTest {
    private val bytes: ByteArray = javaClass.getResourceAsStream("/ultrahdr-mini.jpg")!!.readBytes()
    private val layout = UltraHdrReader.read { ByteArrayInputStream(bytes) }!!
    private val gainBytes = bytes.copyOfRange(layout.gainStart.toInt(), (layout.gainStart + layout.gainLength).toInt())

    /** Hoofdbeeld (met de eigen encoder en de kop van HDR behouden) met daarachter de herschreven gain map, zoals `Converter.keepHdr` het maakt. */
    private fun build(strength: Float, gain: ByteArray = UltraHdrWriter.rewriteGainMap(gainBytes, layout.meta, strength)): ByteArray {
        val header = OutputMetadata.ultraHdr(layout.primary, gain.size)
        val temp = ByteArrayOutputStream()
        JpegEncoder(temp, 64, 48, 90, true, header.segments).also { it.writeRows(ByteArray(64 * 48 * 3) { i -> (i % 251).toByte() }, 0, 48); it.finish() }
        val primary = temp.toByteArray()
        assertEquals(0xFF.toByte(), primary[header.mpfOffset.toInt()])
        assertEquals(0xE2.toByte(), primary[header.mpfOffset.toInt() + 1])
        val mpf = UltraHdrWriter.mpf(primary.size.toLong(), header.mpfOffset, gain.size.toLong())
        System.arraycopy(mpf, 0, primary, header.mpfOffset.toInt(), mpf.size)
        return primary + gain
    }

    private fun read(data: ByteArray) = UltraHdrReader.read { ByteArrayInputStream(data) }

    @Test fun `de uitvoer is weer te lezen als Ultra HDR, met de sterkte in de waarden`() {
        val file = build(0.5f)
        val l = read(file)!!
        assertEquals(64, l.primary.width)
        assertEquals(16, l.gain.width)
        assertEquals(l.primaryLength, l.gainStart)
        assertEquals(file.size.toLong(), l.gainStart + l.gainLength)
        // Alle gains (log2) keer de helft; gamma, offsets en capaciteit blijven.
        assertArrayEquals(floatArrayOf(0.75f, 1f, 1.25f), l.meta.max, 1e-5f)
        assertArrayEquals(floatArrayOf(-0.05f, -0.05f, -0.05f), l.meta.min, 1e-5f)
        assertArrayEquals(layout.meta.gamma, l.meta.gamma, 0f)
        assertArrayEquals(layout.meta.offSdr, l.meta.offSdr, 1e-6f)
        assertArrayEquals(layout.meta.offHdr, l.meta.offHdr, 1e-6f)
        assertEquals(layout.meta.capMax, l.meta.capMax, 1e-5f)
    }

    @Test fun `de container-xmp van het hoofdbeeld noemt de lengte van de gain map`() {
        val gain = UltraHdrWriter.rewriteGainMap(gainBytes, layout.meta, 1f)
        val xmp = read(build(1f, gain))!!.primary.xmp()!!
        assertTrue(xmp, "Item:Length=\"${gain.size}\"" in xmp)
        assertTrue("hdrgm:Version=\"1.0\"" in xmp)
        assertTrue("Item:Semantic=\"GainMap\"" in xmp)
    }

    @Test fun `de beeldgegevens van de gain map blijven byte voor byte staan`() {
        val gain = UltraHdrWriter.rewriteGainMap(gainBytes, layout.meta, 0.3f)
        val oldHead = JpegParser.readHead(ByteArrayInputStream(gainBytes))!!
        val newHead = JpegParser.readHead(ByteArrayInputStream(gain))!!
        assertArrayEquals(gainBytes.copyOfRange(oldHead.sosOffset.toInt(), gainBytes.size), gain.copyOfRange(newHead.sosOffset.toInt(), gain.size))
        assertEquals(oldHead.width, newHead.width)
        assertEquals(oldHead.height, newHead.height)
    }

    @Test fun `sterkte 1 laat de waarden ongewijzigd`() {
        val l = read(build(1f))!!
        assertArrayEquals(layout.meta.max, l.meta.max, 1e-6f)
        assertArrayEquals(layout.meta.min, l.meta.min, 1e-6f)
        assertArrayEquals(layout.meta.offHdr, l.meta.offHdr, 1e-6f)
    }

    @Test fun `de HDR-offset schuift mee naar de SDR-offset`() {
        fun v(x: Float) = floatArrayOf(x, x, x)
        val m = GainMapMeta(v(0f), v(2f), v(1f), v(0.01f), v(0.05f), 0f, 2f, false)
        assertEquals(0.01f, m.scaled(0f).offHdr[0], 1e-7f)
        assertEquals(0.03f, m.scaled(0.5f).offHdr[0], 1e-7f)
        assertEquals(0.05f, m.scaled(1f).offHdr[0], 1e-7f)
        assertEquals(0.01f, m.scaled(0.5f).offSdr[0], 0f)
        assertEquals(1f, m.scaled(0.5f).max[0], 0f)
    }

    @Test fun `de MPF is byte voor byte die van de Oppo`() {
        // Uit IMG20261113111455.jpg: hoofdbeeld 46437731 bytes, MPF-segment op 23429, gain map 3168205 bytes.
        val expected = hex("FFE20058" + OPPO_MPF)
        assertArrayEquals(expected, UltraHdrWriter.mpf(46437731L, 23429L, 3168205L))
    }

    @Test fun `het ISO-blok heeft de indeling van het ISO-blok van de Oppo`() {
        val real = parseIso(hex(OPPO_ISO))
        assertEquals(0x40, real.flags)
        assertEquals(1, real.channels.size)
        assertEquals(0.0, real.base, 0.0)
        // Dezelfde waarden als in de XMP van de Oppo; ons blok moet er weer hetzelfde uit komen.
        val m = GainMapXmp.parse(OPPO_GAIN_XMP)!!
        val mine = UltraHdrWriter.isoBlock(m, real.flags)
        assertEquals(hex(OPPO_ISO).size, mine.size)
        val parsed = parseIso(mine)
        assertEquals(real.flags, parsed.flags)
        assertEquals(real.base, parsed.base, 1e-6)
        assertEquals(real.alt, parsed.alt, 1e-5)
        assertEquals(1, parsed.channels.size)
        for (k in 0 until 5) assertEquals("veld $k", real.channels[0][k], parsed.channels[0][k], 1e-5)
    }

    @Test fun `verschillende kanalen geven een meerkanaals ISO-blok`() {
        val block = UltraHdrWriter.isoBlock(layout.meta)
        assertEquals(28 + 4 + 1 + 16 + 3 * 40, block.size)
        val parsed = parseIso(block)
        assertEquals(0xC0, parsed.flags)
        assertEquals(3, parsed.channels.size)
        assertEquals(1.5, parsed.channels[0][1], 1e-6)
        assertEquals(2.0, parsed.channels[1][1], 1e-6)
        assertEquals(2.5, parsed.channels[2][1], 1e-6)
        assertEquals(-0.1, parsed.channels[0][0], 1e-6)
    }

    @Test fun `een bestaand ISO-blok in de gain map wordt vervangen, niet verdubbeld`() {
        // Het ISO-blok van de Oppo achter de XMP van de gain map zetten, zoals de camera's het hebben.
        val head = JpegParser.readHead(ByteArrayInputStream(gainBytes))!!
        val xmp = head.segments.first { it.marker == JpegHead.APP1 && it.startsWith(JpegHead.XMP_ID) }
        val after = (xmp.offset + 4 + xmp.length).toInt()
        val withIso = gainBytes.copyOfRange(0, after) + JpegSegments.app(JpegSegments.APP2, hex(OPPO_ISO)) + gainBytes.copyOfRange(after, gainBytes.size)
        val rewritten = UltraHdrWriter.rewriteGainMap(withIso, layout.meta, 0.5f)
        val newHead = JpegParser.readHead(ByteArrayInputStream(rewritten))!!
        val isos = newHead.segments.filter { it.marker == JpegHead.APP2 && it.startsWith(UltraHdrWriter.ISO_URN) }
        assertEquals(1, isos.size)
        val iso = parseIso(isos[0].payload!!)
        assertEquals(0x40 or 0x80, iso.flags)
        assertEquals(3, iso.channels.size)
        assertEquals(0.75, iso.channels[0][1], 1e-5)
        assertEquals(1.25, iso.channels[2][1], 1e-5)
        assertEquals(1, newHead.segments.count { it.marker == JpegHead.APP1 && it.startsWith(JpegHead.XMP_ID) })
        // En de XMP zegt hetzelfde als het ISO-blok.
        val meta = GainMapXmp.parse(newHead.xmp()!!)!!
        assertEquals(0.75f, meta.max[0], 1e-5f)
    }

    @Test fun `zonder XMP in de gain map komt er een`() {
        val head = JpegParser.readHead(ByteArrayInputStream(gainBytes))!!
        val xmp = head.segments.first { it.marker == JpegHead.APP1 && it.startsWith(JpegHead.XMP_ID) }
        val stripped = gainBytes.copyOfRange(0, xmp.offset.toInt()) + gainBytes.copyOfRange((xmp.offset + 4 + xmp.length).toInt(), gainBytes.size)
        val rewritten = UltraHdrWriter.rewriteGainMap(stripped, layout.meta, 1f)
        assertNotNull(GainMapXmp.parse(JpegParser.readHead(ByteArrayInputStream(rewritten))!!.xmp()!!))
    }

    @Test fun `getallen in de XMP zijn platte decimalen`() {
        assertEquals("0", GainMapXmp.number(0f))
        assertEquals("0.00001", GainMapXmp.number(1e-5f))
        assertEquals("2.30045", GainMapXmp.number(2.30045f))
        assertEquals("-0.1", GainMapXmp.number(-0.1f))
        assertEquals("1", GainMapXmp.number(1f))
    }

    @Test fun `de gain-map-XMP die we schrijven leest de parser weer in, ook met kanalen die verschillen`() {
        val one = GainMapXmp.parse(GainMapXmp.build(GainMapXmp.parse(OPPO_GAIN_XMP)!!))!!
        assertEquals(2.30045f, one.max[2], 1e-6f)
        val three = GainMapXmp.parse(GainMapXmp.build(layout.meta))!!
        assertArrayEquals(layout.meta.max, three.max, 1e-6f)
        assertArrayEquals(layout.meta.min, three.min, 1e-6f)
        assertArrayEquals(layout.meta.gamma, three.gamma, 1e-6f)
    }

    // ---- hulp ----

    private class Iso(val flags: Int, val base: Double, val alt: Double, val channels: List<DoubleArray>)

    private fun parseIso(payload: ByteArray): Iso {
        val b = ByteBuffer.wrap(payload)
        val urn = ByteArray(UltraHdrWriter.ISO_URN.length).also { b.get(it) }
        assertEquals(UltraHdrWriter.ISO_URN, String(urn, Charsets.ISO_8859_1))
        assertEquals(0, b.short.toInt()); assertEquals(0, b.short.toInt())
        val flags = b.get().toInt() and 0xFF
        fun u() = (b.int.toLong() and 0xFFFFFFFFL).toDouble()
        val base = u() / u()
        val alt = u() / u()
        val n = if (flags and 0x80 != 0) 3 else 1
        val channels = List(n) {
            val v = DoubleArray(5)
            // min en max zijn getekend, gamma niet, de offsets zijn weer getekend.
            for (k in 0 until 5) { val num = if (k == 2) u() else b.int.toDouble(); v[k] = num / u() }
            v
        }
        assertEquals(0, b.remaining())
        return Iso(flags, base, alt, channels)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        /** De MPF-payload van de Oppo (86 bytes), zonder FFE2 en lengte. */
        const val OPPO_MPF = "4d5046004d4d002a000000080003b00000070000000430313030b00100040000000100000002b00200070000002000000032" +
            "000000000003000002c49563000000000000000000000000003057cd02c439d600000000"

        /** Het ISO-blok van de gain map van dezelfde foto (89 bytes): één kanaal, min 0, max 2412195/1048576, alles op 0 of 1. */
        const val OPPO_ISO = "75726e3a69736f3a7374643a69736f3a74733a32313439363a2d3100000000004000000000000000010024cea3" +
            "0010000000000000000000010024cea300100000000000010000000100000000000000010000000000000001"

        const val OPPO_GAIN_XMP = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
      hdrgm:Version="1.0" hdrgm:GainMapMin="0" hdrgm:GainMapMax="2.30045" hdrgm:Gamma="1" hdrgm:OffsetSDR="0" hdrgm:OffsetHDR="0"
      hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="2.30045" hdrgm:BaseRenditionIsHDR="False"/>
  </rdf:RDF>
</x:xmpmeta>"""
    }
}
