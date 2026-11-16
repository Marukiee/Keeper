package nl.markmaaktmedia.keeper.convert

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegEncoderTest {
    private fun gradient(w: Int, h: Int): ByteArray = ByteArray(w * h * 3).also { d ->
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            d[i] = (x * 255 / maxOf(w - 1, 1)).toByte()
            d[i + 1] = (y * 255 / maxOf(h - 1, 1)).toByte()
            d[i + 2] = ((x + y) * 255 / maxOf(w + h - 2, 1)).toByte()
        }
    }

    private fun encode(rgb: ByteArray, w: Int, h: Int, q: Int, c420: Boolean, segments: List<ByteArray> = emptyList(), rowsPerCall: Int = 5): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = JpegEncoder(out, w, h, q, c420, segments)
        var y = 0
        while (y < h) { val n = minOf(rowsPerCall, h - y); enc.writeRows(rgb, y * w * 3, n); y += n }
        enc.finish()
        return out.toByteArray()
    }

    private fun psnr(rgb: ByteArray, dec: JdkJpeg.Decoded): Double {
        var se = 0.0
        for (i in 0 until dec.width * dec.height) {
            val p = dec.argb[i]
            val dr = (rgb[i * 3].toInt() and 0xFF) - ((p shr 16) and 0xFF)
            val dg = (rgb[i * 3 + 1].toInt() and 0xFF) - ((p shr 8) and 0xFF)
            val db = (rgb[i * 3 + 2].toInt() and 0xFF) - (p and 0xFF)
            se += dr * dr + dg * dg + db * db
        }
        val mse = se / (dec.width * dec.height * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    @Test fun `terug te lezen als 4-4-4 en 4-2-0, ook bij maten die niet op een blok vallen`() {
        for ((w, h) in listOf(64 to 48, 37 to 21, 1 to 1, 17 to 3, 130 to 70)) for (c420 in listOf(true, false)) {
            val rgb = gradient(w, h)
            val dec = JdkJpeg.decode(encode(rgb, w, h, 94, c420))
            assertEquals("breedte $w", w, dec.width)
            assertEquals("hoogte $h", h, dec.height)
            if (w >= 37) assertTrue("psnr ${psnr(rgb, dec)} bij ${w}x$h 420=$c420", psnr(rgb, dec) > if (c420) 30.0 else 36.0)
        }
    }

    @Test fun `scherpe randen in grijs blijven scherp ook bij 4-2-0`() {
        val w = 64; val h = 48
        val rgb = ByteArray(w * h * 3) { i -> val p = i / 3; (if (((p % w) / 8 + (p / w) / 8) % 2 == 0) 60 else 190).toByte() }
        for (c420 in listOf(true, false)) {
            val dec = JdkJpeg.decode(encode(rgb, w, h, 94, c420))
            assertTrue("psnr ${psnr(rgb, dec)} 420=$c420", psnr(rgb, dec) > 38.0)
        }
    }

    @Test fun `ruis met veel FF-bytes wordt goed gestuffd en blijft leesbaar`() {
        val w = 96; val h = 80
        val rgb = Random(7).nextBytes(w * h * 3)
        val data = encode(rgb, w, h, 100, false)
        val dec = JdkJpeg.decode(data)
        assertTrue("psnr ${psnr(rgb, dec)}", psnr(rgb, dec) > 38.0)
    }

    @Test fun `kwantisatietabellen zijn gelijk aan die van libjpeg bij q94`() {
        val data = encode(gradient(16, 16), 16, 16, 94, true)
        val dqt = findSegment(data, 0xDB)
        // PIL geeft de tabellen in natuurlijke volgorde, het bestand heeft zigzag
        fun natural(at: Int) = IntArray(64).also { t -> for (k in 0 until 64) t[JpegTables.ZIGZAG[k]] = dqt[at + k].toInt() and 0xFF }
        val luma = natural(1)
        val chroma = natural(66)
        assertArrayEquals(intArrayOf(2, 1, 1, 2, 3, 5, 6, 7), luma.copyOf(8))
        assertEquals(441, luma.sum())
        assertArrayEquals(intArrayOf(2, 2, 3, 6, 12, 12, 12, 12), chroma.copyOf(8))
        assertEquals(668, chroma.sum())
    }

    @Test fun `meegegeven segmenten staan direct na SOI en de kop blijft te lezen`() {
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 16) + "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(8) { it.toByte() }
        val icc = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, 18) + "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1) + ByteArray(2)
        val data = encode(gradient(40, 24), 40, 24, 90, true, listOf(exif, icc))
        val head = JpegParser.readHead(ByteArrayInputStream(data))!!
        assertEquals(40, head.width); assertEquals(24, head.height); assertEquals(3, head.components)
        assertEquals(listOf(0xE1, 0xE2, 0xDB, 0xC0, 0xC4), head.segments.map { it.marker })
        assertArrayEquals(ByteArray(8) { it.toByte() }, head.exifTiff())
        assertNotNull(JdkJpeg.decode(data))
    }

    @Test fun `te weinig of te veel rijen is een fout`() {
        val out = ByteArrayOutputStream()
        val enc = JpegEncoder(out, 8, 8, 90)
        enc.writeRows(gradient(8, 8), 0, 4)
        val r = runCatching { enc.finish() }
        assertTrue(r.isFailure)
    }

    private fun findSegment(data: ByteArray, marker: Int): ByteArray {
        var p = 2
        while (p + 4 <= data.size) {
            val m = data[p + 1].toInt() and 0xFF
            val len = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
            if (m == marker) return data.copyOfRange(p + 4, p + 2 + len)
            p += 2 + len
        }
        error("segment niet gevonden")
    }
}
