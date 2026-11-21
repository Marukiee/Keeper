package nl.markmaaktmedia.keeper.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResolutionTest {
    private fun image(w: Int, h: Int, f: (x: Int, y: Int, c: Int) -> Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) out[(y * w + x) * 3 + c] = f(x, y, c).toByte()
        return out
    }

    /** Alles in één keer, of in stukken van [chunk] rijen. */
    private fun resample(src: ByteArray, w: Int, h: Int, outW: Int, outH: Int, chunk: Int): ByteArray {
        val r = Resampler(w, h, outW, outH, 3, chunk)
        val out = java.io.ByteArrayOutputStream()
        var y = 0
        while (y < h) {
            val n = minOf(chunk, h - y)
            r.push(src.copyOfRange(y * w * 3, (y + n) * w * 3), n) { buf, rows -> out.write(buf, 0, rows * outW * 3) }
            y += n
        }
        assertEquals(outH, r.rowsOut)
        return out.toByteArray()
    }

    private fun at(img: ByteArray, w: Int, x: Int, y: Int, c: Int) = img[(y * w + x) * 3 + c].toInt() and 0xFF

    @Test fun resamplerMatchesPillowLanczos() {
        // Referentie: Pillow's Image.resize((28, 21), LANCZOS) op dit beeld; Pillow rondt tussen de twee stappen af, dus een kleine afwijking is goed.
        val src = image(40, 30) { x, y, c -> (x * 7 + y * 13 + c * 50) % 256 }
        val out = resample(src, 40, 30, 28, 21, chunk = 8)
        val points = listOf(0 to 0, 5 to 3, 27 to 0, 0 to 20, 13 to 10, 27 to 20, 20 to 7, 9 to 15)
        val expected = listOf(
            intArrayOf(5, 55, 105), intArrayOf(110, 160, 207), intArrayOf(19, 68, 118), intArrayOf(120, 170, 220),
            intArrayOf(64, 114, 164), intArrayOf(133, 183, 233), intArrayOf(78, 128, 178), intArrayOf(117, 167, 212),
        )
        for ((i, p) in points.withIndex()) for (c in 0 until 3) {
            val got = at(out, 28, p.first, p.second, c)
            assertTrue("punt ${p} kanaal $c: $got, verwacht ${expected[i][c]}", abs(got - expected[i][c]) <= 2)
        }
    }

    @Test fun resamplerMatchesPillowOnASmoothRamp() {
        val src = image(64, 48) { x, y, c -> minOf(255, x * 3 + y * 2 + c * 10) }
        val out = resample(src, 64, 48, 45, 34, chunk = 16)
        val points = listOf(0 to 0, 10 to 10, 44 to 33, 22 to 17, 44 to 0, 0 to 33)
        val expected = listOf(
            intArrayOf(1, 11, 21), intArrayOf(72, 82, 92), intArrayOf(255, 255, 255), intArrayOf(143, 153, 163), intArrayOf(188, 198, 208), intArrayOf(95, 105, 115),
        )
        for ((i, p) in points.withIndex()) for (c in 0 until 3) {
            val got = at(out, 45, p.first, p.second, c)
            assertTrue("punt ${p} kanaal $c: $got, verwacht ${expected[i][c]}", abs(got - expected[i][c]) <= 2)
        }
    }

    @Test fun stripsGiveTheSameResultAsOneGo() {
        val src = image(97, 83) { x, y, c -> (x * 5 + y * 3 + c * 40 + (x * y) % 17) % 256 }
        val whole = resample(src, 97, 83, 69, 59, chunk = 83)
        for (chunk in listOf(1, 7, 16, 40)) {
            assertTrue("stukken van $chunk rijen", whole.contentEquals(resample(src, 97, 83, 69, 59, chunk)))
        }
    }

    @Test fun aFlatImageStaysFlat() {
        val out = resample(image(50, 40) { _, _, c -> 100 + c * 20 }, 50, 40, 35, 28, chunk = 10)
        for (i in out.indices) assertEquals(100 + (i % 3) * 20, out[i].toInt() and 0xFF)
    }

    @Test fun halfThePixelsMeansAFactorOfRootTwoPerSide() {
        val pixels = Math.round(8192 * ResolutionCheck.HALF).toLong() * Math.round(6144 * ResolutionCheck.HALF)
        assertTrue(abs(pixels - 8192L * 6144 / 2) < 20_000)
    }

    /** Een stukje van [ResolutionCheck.CELL] x CELL met [f] als luminantie. */
    private fun cell(f: (x: Int, y: Int) -> Int): ByteArray = ByteArray(ResolutionCheck.CELL * ResolutionCheck.CELL) { f(it % ResolutionCheck.CELL, it / ResolutionCheck.CELL).toByte() }

    @Test fun aSmoothCellLosesNothingAtHalfThePixels() {
        val smooth = cell { x, y -> 60 + (x + y) / 4 }
        assertTrue(ResolutionCheck.lossAtHalf(smooth) > 50)
    }

    @Test fun pixelNoiseCellLosesALot() {
        val rnd = java.util.Random(7)
        val noise = cell { _, _ -> rnd.nextInt(256) }
        assertTrue("ruis: ${ResolutionCheck.lossAtHalf(noise)}", ResolutionCheck.lossAtHalf(noise) < 20)
    }

    @Test fun fineDetailScoresLowerThanCoarseDetail() {
        // Een streeppatroon met een periode van 2 pixels is fijner dan wat de helft van de pixels kan dragen; een van 24 pixels niet.
        val fine = cell { x, _ -> if (x % 2 == 0) 40 else 200 }
        val coarse = cell { x, _ -> if (x / 12 % 2 == 0) 40 else 200 }
        assertTrue(ResolutionCheck.lossAtHalf(fine) < ResolutionCheck.lossAtHalf(coarse) - 5)
    }

    @Test fun samplerKeepsTheMostDetailedCellsAndScoresThemByTheWorstFifth() {
        val w = ResolutionCheck.CELL * 4
        val rowBytes = w * 4
        val raw = ByteArray(rowBytes * ResolutionCheck.CELL)
        val rnd = java.util.Random(3)
        // Drie gladde stukjes en een vol ruis.
        for (y in 0 until ResolutionCheck.CELL) for (x in 0 until w) {
            val v = if (x / ResolutionCheck.CELL == 2) rnd.nextInt(256) else 100 + x / 20
            val p = y * rowBytes + x * 4
            raw[p] = v.toByte(); raw[p + 1] = v.toByte(); raw[p + 2] = v.toByte(); raw[p + 3] = 0xFF.toByte()
        }
        val sampler = ResolutionCheck.Sampler(w, keep = 1)
        sampler.addStrip(raw, rowBytes)
        val onlyNoise = sampler.score()!!
        assertTrue("alleen het ruisstukje blijft: $onlyNoise", onlyNoise < 20)
        val all = ResolutionCheck.Sampler(w, keep = 4).also { it.addStrip(raw, rowBytes) }.score()!!
        // Het slechtste vijfde van vier stukjes is het ruisstukje.
        assertTrue("met alle vier: $all", all < 20)
    }

    @Test fun onlyBigPhotosWithAHighScoreAreHalved() {
        val big = 50_000_000L
        assertTrue(ResolutionCheck.decide(big, ResolutionCheck.THRESHOLD_DB + 1).halve)
        assertFalse(ResolutionCheck.decide(big, ResolutionCheck.THRESHOLD_DB - 1).halve)
        assertFalse(ResolutionCheck.decide(12_000_000L, 99.0).halve)
    }

    @Test fun exifDimensionsArePatchedInPlace() {
        // Een minimale little-endian Exif: IFD0 met ImageWidth (SHORT), ImageLength (LONG) en een Exif-IFD-aanwijzer; de Exif-IFD heeft PixelXDimension en PixelYDimension (LONG).
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = le16(v and 0xFFFF) + le16(v ushr 16)
        fun entry(tag: Int, type: Int, value: Int) = le16(tag) + le16(type) + le32(1) + (if (type == 3) le16(value) + le16(0) else le32(value))
        val header = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + "II".toByteArray() + le16(42) + le32(8)
        val ifd0 = le16(3) + entry(0x0100, 3, 8192) + entry(0x0101, 4, 6144) + entry(0x8769, 4, 8 + 2 + 3 * 12 + 4) + le32(0)
        val exifIfd = le16(2) + entry(0xA002, 4, 8192) + entry(0xA003, 4, 6144) + le32(0)
        val payload = header + ifd0 + exifIfd
        val out = ExifDimensions.patch(payload, 5793, 4345)
        assertEquals(payload.size, out.size)
        fun u16(p: Int) = (out[p].toInt() and 0xFF) or ((out[p + 1].toInt() and 0xFF) shl 8)
        fun u32(p: Int) = u16(p) or (u16(p + 2) shl 16)
        val tiff = 6
        assertEquals(5793, u16(tiff + 8 + 2 + 8))
        assertEquals(4345, u32(tiff + 8 + 2 + 12 + 8))
        val exif = tiff + 8 + 2 + 3 * 12 + 4
        assertEquals(5793, u32(exif + 2 + 8))
        assertEquals(4345, u32(exif + 2 + 12 + 8))
        // Het origineel is niet aangepast.
        assertEquals(8192, (payload[tiff + 8 + 2 + 8].toInt() and 0xFF) or ((payload[tiff + 8 + 2 + 9].toInt() and 0xFF) shl 8))
    }
}
