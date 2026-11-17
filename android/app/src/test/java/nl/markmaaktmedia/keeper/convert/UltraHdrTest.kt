package nl.markmaaktmedia.keeper.convert

import java.io.ByteArrayInputStream
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Het testbestand is gemaakt met `prototype/maak_testbestand.py`; de verwachte waarden komen uit het Python-prototype (`uhdr.py`). */
class UltraHdrTest {
    private val bytes: ByteArray = javaClass.getResourceAsStream("/ultrahdr-mini.jpg")!!.readBytes()

    private fun layout(data: ByteArray = bytes) = UltraHdrReader.read { ByteArrayInputStream(data) }

    @Test fun `leest hoofdbeeld, gain map en parameters`() {
        val l = layout()!!
        assertEquals(64, l.primary.width)
        assertEquals(48, l.primary.height)
        assertEquals(16, l.gain.width)
        assertEquals(12, l.gain.height)
        assertEquals(l.primaryLength, l.gainStart)
        assertEquals(bytes.size.toLong(), l.gainStart + l.gainLength)
        assertArrayEquals(floatArrayOf(1.5f, 2f, 2.5f), l.meta.max, 0f)
        assertArrayEquals(floatArrayOf(-0.1f, -0.1f, -0.1f), l.meta.min, 0f)
        assertArrayEquals(floatArrayOf(1.5f, 1.5f, 1.5f), l.meta.gamma, 0f)
        assertEquals(0.015625f, l.meta.offSdr[0], 0f)
        assertFalse(l.meta.baseIsHdr)
    }

    @Test fun `bestand zonder gain map of geen JPEG geeft null`() {
        val l = layout()!!
        assertNull(layout(bytes.copyOf(l.primaryLength.toInt())))
        assertNull(layout(ByteArray(100) { 7 }))
        assertNull(layout(bytes.copyOf(l.primaryLength.toInt() + 30)))
    }

    @Test fun `xmp met attributen en met lijsten per kanaal`() {
        val attr = GainMapXmp.parse("""<rdf:Description hdrgm:GainMapMax="2.3" hdrgm:Gamma="1" hdrgm:OffsetSDR="0"/>""")!!
        assertArrayEquals(floatArrayOf(2.3f, 2.3f, 2.3f), attr.max, 0f)
        assertEquals(0f, attr.offSdr[1], 0f)
        assertEquals(0.015625f, attr.offHdr[1], 0f)
        val seq = GainMapXmp.parse("<hdrgm:GainMapMin><rdf:Seq><rdf:li>0</rdf:li><rdf:li>0.5</rdf:li><rdf:li>1</rdf:li></rdf:Seq></hdrgm:GainMapMin>")!!
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), seq.min, 0f)
        assertNull(GainMapXmp.parse("<x:xmpmeta>niets</x:xmpmeta>"))
    }

    @Test fun `hdr uit sdr en gain map komt overeen met het prototype`() {
        val l = layout()!!
        val base = JdkJpeg.decode(bytes.copyOfRange(0, l.primaryLength.toInt()))
        val gm = JdkJpeg.decode(bytes.copyOfRange(l.gainStart.toInt(), (l.gainStart + l.gainLength).toInt()))
        val gain = GainMapImage(gm.width, gm.height, 3, ByteArray(gm.width * gm.height * 3).also { d ->
            for (i in 0 until gm.width * gm.height) {
                val p = gm.argb[i]
                d[i * 3] = (p shr 16).toByte(); d[i * 3 + 1] = (p shr 8).toByte(); d[i * 3 + 2] = p.toByte()
            }
        })
        val sampler = GainMapSampler(l.meta, gain, base.width, base.height)
        assertTrue(sampler.hasGainMap)
        // (x, y) -> verwachte hdr uit Python
        val expected = mapOf(
            (0 to 0) to floatArrayOf(0.0f, 0.001425f, 0.005481f),
            (10 to 5) to floatArrayOf(0.030092f, 0.016497f, 0.051392f),
            (31 to 23) to floatArrayOf(0.38288f, 0.393257f, 0.606184f),
            (63 to 47) to floatArrayOf(2.645803f, 3.028711f, 4.522868f),
        )
        val sdr = FloatArray(base.width * 3)
        val hdr = FloatArray(base.width * 3)
        for ((xy, want) in expected) {
            val (x, y) = xy
            for (px in 0 until base.width) {
                val p = base.argb[y * base.width + px]
                sdr[px * 3] = Srgb.toLinear[(p shr 16) and 0xFF]
                sdr[px * 3 + 1] = Srgb.toLinear[(p shr 8) and 0xFF]
                sdr[px * 3 + 2] = Srgb.toLinear[p and 0xFF]
            }
            sampler.hdrRow(y, sdr, hdr)
            for (c in 0 until 3) {
                val tol = 0.003f + 0.02f * want[c] // ImageIO en libjpeg-turbo wijken een tikje af
                assertTrue("pixel $xy kanaal $c: ${hdr[x * 3 + c]} i.p.v. ${want[c]}", abs(hdr[x * 3 + c] - want[c]) <= tol)
            }
        }
    }

    @Test fun `zonder gain map blijft de rij ongewijzigd`() {
        val sampler = GainMapSampler(null, null, 4, 2)
        val row = floatArrayOf(0.1f, 0.2f, 0.3f, 1f, 1f, 1f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f)
        val out = FloatArray(12)
        sampler.hdrRow(0, row, out)
        assertArrayEquals(row, out, 0f)
    }

    /** Gain map van 2x2 met links 0 en rechts 255 (één kanaal), log2-gain van 0 tot 2 (dus links x1, rechts x4), en de gegeven offsets. */
    private fun tiny(offSdr: Float = 0f, offHdr: Float = 0f): Pair<GainMapMeta, GainMapImage> {
        fun v(x: Float) = floatArrayOf(x, x, x)
        val meta = GainMapMeta(v(0f), v(2f), v(1f), v(offSdr), v(offHdr), 0f, 2f, false)
        return meta to GainMapImage(2, 2, 1, byteArrayOf(0, 255.toByte(), 0, 255.toByte()))
    }

    private fun row(meta: GainMapMeta, gain: GainMapImage, strength: Float): FloatArray {
        val sampler = GainMapSampler(meta, gain, 2, 2, strength)
        val sdr = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f)
        val out = FloatArray(6)
        sampler.hdrRow(0, sdr, out)
        return out
    }

    @Test fun `sterkte 1 is de volle HDR, sterkte 0 geeft de SDR van de camera terug`() {
        val (meta, gain) = tiny()
        val full = row(meta, gain, 1f)
        val default = GainMapSampler(meta, gain, 2, 2).let { s -> FloatArray(6).also { s.hdrRow(0, floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f), it) } }
        assertArrayEquals(default, full, 0f)
        assertEquals(0.2f * 4f, full[3], 0.02f) // rechter pixel: gain-map-waarde 255 = 2^2
        val none = row(meta, gain, 0f)
        for (v in none) assertEquals(0.2f, v, 1e-6f)
    }

    @Test fun `de sterkte schaalt de gain in log2 dus de helft is de wortel van de volle boost`() {
        val (meta, gain) = tiny()
        val full = row(meta, gain, 1f)
        val half = row(meta, gain, 0.5f)
        for (i in 0 until 6) assertEquals("kanaal $i", 0.2f * Math.sqrt((full[i] / 0.2f).toDouble()).toFloat(), half[i], 1e-4f)
    }

    @Test fun `bij verschillende offsets geeft sterkte 0 ook precies de SDR terug`() {
        val (meta, gain) = tiny(offSdr = 0.015625f, offHdr = 0f)
        for (v in row(meta, gain, 0f)) assertEquals(0.2f, v, 1e-6f)
        // En de volle sterkte is ongewijzigd: (sdr + offSdr) * factor - offHdr.
        val full = row(meta, gain, 1f)
        assertEquals((0.2f + 0.015625f) * 4f, full[3], 0.02f)
    }

    @Test fun `sRGB heen en terug is exact voor alle 8-bit waarden`() {
        for (v in 0..255) assertEquals("waarde $v", v, Srgb.toByte(Srgb.toLinear[v]))
        assertEquals(255, Srgb.toByte(7f))
        assertEquals(0, Srgb.toByte(-1f))
        assertNotNull(Srgb.toLinear)
    }
}
