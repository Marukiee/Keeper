package nl.markmaaktmedia.keeper.convert

import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Baseline-JPEG-encoder die rij voor rij werkt, zodat een foto van 200 MP nooit als geheel in het geheugen staat.
 * Standaard Huffman-tabellen (Annex K), kwantisatie zoals libjpeg bij een kwaliteit van 1 tot 100, float-DCT (AAN).
 *
 * Gebruik: `writeRows` met RGB (3 bytes per pixel, sRGB-getransfereerd, in het gamut van het ICC-profiel) tot alle
 * [height] rijen binnen zijn, dan `finish`. [segments] zijn complete APPn-segmenten (met FFxx en lengte) die direct na SOI komen.
 */
class JpegEncoder(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    quality: Int,
    private val chroma420: Boolean = true,
    segments: List<ByteArray> = emptyList(),
    /**
     * Waar MCU-rijen naast elkaar op gecodeerd worden. Elke MCU-rij is een eigen restart-interval (DRI), dus ze zijn los van elkaar te
     * coderen; de bytes komen er daarna in volgorde uit. De pixels zijn dezelfde als bij één thread (zelfde DCT, kwantisatie en Huffman),
     * alleen het bestand heeft er een RSTn-markering bij per MCU-rij. Zonder pool gebeurt alles op de aanroepende thread.
     */
    private val pool: ExecutorService? = null,
) {
    private val mcu = if (chroma420) 16 else 8
    private val padW = (width + mcu - 1) / mcu * mcu
    private val mcuCols = padW / mcu
    private val mcuRows = (height + mcu - 1) / mcu

    private val quantLuma = scaledTable(JpegTables.LUMA_Q50, quality)
    private val quantChroma = scaledTable(JpegTables.CHROMA_Q50, quality)
    private val divLuma = divisors(quantLuma)
    private val divChroma = divisors(quantChroma)

    private val dcLuma = HuffCodes(JpegTables.DC_LUMA_COUNTS, JpegTables.DC_LUMA_SYMBOLS)
    private val dcChroma = HuffCodes(JpegTables.DC_CHROMA_COUNTS, JpegTables.DC_CHROMA_SYMBOLS)
    private val acLuma = HuffCodes(JpegTables.AC_LUMA_COUNTS, JpegTables.AC_LUMA_SYMBOLS)
    private val acChroma = HuffCodes(JpegTables.AC_CHROMA_COUNTS, JpegTables.AC_CHROMA_SYMBOLS)

    /** Elke thread die codeert heeft zijn eigen werkruimte (zo'n twee MB bij 50 MP). */
    private val coders = ThreadLocal.withInitial { RowCoder() }

    // Rijen die nog geen volle MCU-rij vormen (alleen als de aanroeper geen veelvoud van de MCU-hoogte aanlevert).
    private val rowBytes = width * 3
    private val waiting = ByteArray(rowBytes * mcu)
    private var waitingRows = 0
    private var rowsIn = 0
    private var nextRow = 0
    private var finished = false

    init {
        require(width in 1..65535 && height in 1..65535) { "afmeting buiten bereik" }
        writeHeader(segments)
    }

    /** [count] rijen RGB vanaf [offset]; elke rij is `width * 3` bytes en ze liggen aaneen. Komt terug als alles gecodeerd en geschreven is. */
    fun writeRows(rgb: ByteArray, offset: Int, count: Int) {
        check(!finished) { "al afgerond" }
        check(rowsIn + count <= height) { "meer rijen dan de hoogte" }
        val jobs = ArrayList<Future<ByteArray>>()
        var o = offset
        var left = count
        while (left > 0) {
            if (waitingRows == 0 && left >= mcu) {
                // Hele MCU-rijen rechtstreeks uit het buffer van de aanroeper.
                jobs += submit(rgb, o, mcu)
                o += mcu * rowBytes; left -= mcu; rowsIn += mcu
            } else {
                val take = minOf(mcu - waitingRows, left)
                System.arraycopy(rgb, o, waiting, waitingRows * rowBytes, take * rowBytes)
                waitingRows += take; o += take * rowBytes; left -= take; rowsIn += take
                if (waitingRows == mcu) {
                    // Een kopie, want `waiting` wordt meteen weer gevuld terwijl de taak nog loopt.
                    jobs += submit(waiting.copyOf(), 0, mcu)
                    waitingRows = 0
                }
            }
        }
        jobs.forEach { out.write(it.await()) }
    }

    fun finish() {
        check(!finished) { "al afgerond" }
        check(rowsIn == height) { "er ontbreken rijen: $rowsIn van $height" }
        // De laatste, onvolle MCU-rij: de coder herhaalt de laatste rij tot hij vol is.
        if (waitingRows > 0) { out.write(submit(waiting, 0, waitingRows).await()); waitingRows = 0 }
        out.write(0xFF); out.write(0xD9)
        out.flush()
        finished = true
    }

    private fun submit(rgb: ByteArray, offset: Int, rows: Int): Future<ByteArray> {
        val index = nextRow++
        val task = Callable { coders.get().encode(index, rgb, offset, rows) }
        return pool?.submit(task) ?: CompletableFuture.completedFuture(task.call())
    }

    private fun <T> Future<T>.await(): T = try { get() } catch (e: ExecutionException) { throw e.cause ?: e }

    // ---- kop ----

    private fun writeHeader(segments: List<ByteArray>) {
        out.write(0xFF); out.write(0xD8)
        for (s in segments) {
            require(s.size >= 4 && s[0] == 0xFF.toByte()) { "geen compleet segment" }
            out.write(s)
        }
        // DQT: twee tabellen in zigzag-volgorde
        val dqt = ByteArray(4 + 2 * 65)
        dqt[0] = 0xFF.toByte(); dqt[1] = 0xDB.toByte(); put16(dqt, 2, dqt.size - 2)
        for ((t, q) in listOf(quantLuma, quantChroma).withIndex()) {
            dqt[4 + t * 65] = t.toByte()
            for (k in 0 until 64) dqt[5 + t * 65 + k] = q[JpegTables.ZIGZAG[k]].toByte()
        }
        out.write(dqt)
        // SOF0
        val sof = ByteArray(19)
        sof[0] = 0xFF.toByte(); sof[1] = 0xC0.toByte(); put16(sof, 2, 17); sof[4] = 8
        put16(sof, 5, height); put16(sof, 7, width); sof[9] = 3
        sof[10] = 1; sof[11] = (if (chroma420) 0x22 else 0x11).toByte(); sof[12] = 0
        sof[13] = 2; sof[14] = 0x11; sof[15] = 1
        sof[16] = 3; sof[17] = 0x11; sof[18] = 1
        out.write(sof)
        // DHT: alle vier in één segment
        val tables = listOf(
            0x00 to (JpegTables.DC_LUMA_COUNTS to JpegTables.DC_LUMA_SYMBOLS),
            0x10 to (JpegTables.AC_LUMA_COUNTS to JpegTables.AC_LUMA_SYMBOLS),
            0x01 to (JpegTables.DC_CHROMA_COUNTS to JpegTables.DC_CHROMA_SYMBOLS),
            0x11 to (JpegTables.AC_CHROMA_COUNTS to JpegTables.AC_CHROMA_SYMBOLS),
        )
        val body = java.io.ByteArrayOutputStream()
        for ((id, t) in tables) {
            body.write(id)
            t.first.forEach { body.write(it) }
            t.second.forEach { body.write(it) }
        }
        val dht = ByteArray(4)
        dht[0] = 0xFF.toByte(); dht[1] = 0xC4.toByte(); put16(dht, 2, body.size() + 2)
        out.write(dht); out.write(body.toByteArray())
        // DRI: na elke MCU-rij een restart, zodat de rijen los van elkaar te coderen zijn.
        out.write(byteArrayOf(0xFF.toByte(), 0xDD.toByte(), 0, 4, (mcuCols shr 8).toByte(), mcuCols.toByte()))
        // SOS
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 12, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0))
    }

    private fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte() }

    /** Eén MCU-rij van RGB naar de gecodeerde bytes van dat restart-interval, met de RSTn erachter (behalve na de laatste). */
    private inner class RowCoder {
        // De rijen van één MCU-rij, op volle resolutie, met 128 al afgetrokken.
        private val planeY = Array(mcu) { FloatArray(padW) }
        private val planeCb = Array(mcu) { FloatArray(padW) }
        private val planeCr = Array(mcu) { FloatArray(padW) }
        private val block = FloatArray(64)
        private val coef = IntArray(64)
        private var buffer = ByteArray(1 shl 16)
        private var bufferLen = 0
        private var acc = 0L
        private var accBits = 0
        private var prevDcY = 0
        private var prevDcCb = 0
        private var prevDcCr = 0

        fun encode(index: Int, rgb: ByteArray, offset: Int, rows: Int): ByteArray {
            bufferLen = 0; acc = 0; accBits = 0; prevDcY = 0; prevDcCb = 0; prevDcCr = 0
            var o = offset
            for (r in 0 until rows) { convertRow(rgb, o, r); o += rowBytes }
            for (r in rows until mcu) {
                System.arraycopy(planeY[rows - 1], 0, planeY[r], 0, padW)
                System.arraycopy(planeCb[rows - 1], 0, planeCb[r], 0, padW)
                System.arraycopy(planeCr[rows - 1], 0, planeCr[r], 0, padW)
            }
            encodeMcuRow()
            if (accBits > 0) putBits((1 shl (8 - accBits)) - 1, 8 - accBits) // opvullen met 1-bits
            if (index < mcuRows - 1) {
                if (bufferLen > buffer.size - 2) buffer = buffer.copyOf(buffer.size * 2)
                buffer[bufferLen++] = 0xFF.toByte()
                buffer[bufferLen++] = (0xD0 + index % 8).toByte()
            }
            return buffer.copyOf(bufferLen)
        }

        private fun convertRow(rgb: ByteArray, offset: Int, row: Int) {
            val yp = planeY[row]
            val cbp = planeCb[row]
            val crp = planeCr[row]
            var o = offset
            for (x in 0 until width) {
                val r = (rgb[o].toInt() and 0xFF).toFloat()
                val g = (rgb[o + 1].toInt() and 0xFF).toFloat()
                val b = (rgb[o + 2].toInt() and 0xFF).toFloat()
                o += 3
                yp[x] = 0.299f * r + 0.587f * g + 0.114f * b - 128f
                cbp[x] = -0.168736f * r - 0.331264f * g + 0.5f * b
                crp[x] = 0.5f * r - 0.418688f * g - 0.081312f * b
            }
            for (x in width until padW) { yp[x] = yp[width - 1]; cbp[x] = cbp[width - 1]; crp[x] = crp[width - 1] }
        }

        private fun encodeMcuRow() {
            for (m in 0 until mcuCols) {
                if (chroma420) {
                    val x0 = m * 16
                    for (by in 0 until 2) for (bx in 0 until 2) {
                        copyBlock(planeY, by * 8, x0 + bx * 8)
                        prevDcY = codeBlock(divLuma, prevDcY, dcLuma, acLuma)
                    }
                    downsampleBlock(planeCb, x0)
                    prevDcCb = codeBlock(divChroma, prevDcCb, dcChroma, acChroma)
                    downsampleBlock(planeCr, x0)
                    prevDcCr = codeBlock(divChroma, prevDcCr, dcChroma, acChroma)
                } else {
                    val x0 = m * 8
                    copyBlock(planeY, 0, x0); prevDcY = codeBlock(divLuma, prevDcY, dcLuma, acLuma)
                    copyBlock(planeCb, 0, x0); prevDcCb = codeBlock(divChroma, prevDcCb, dcChroma, acChroma)
                    copyBlock(planeCr, 0, x0); prevDcCr = codeBlock(divChroma, prevDcCr, dcChroma, acChroma)
                }
            }
        }

        private fun copyBlock(plane: Array<FloatArray>, row0: Int, col0: Int) {
            for (i in 0 until 8) System.arraycopy(plane[row0 + i], col0, block, i * 8, 8)
        }

        private fun downsampleBlock(plane: Array<FloatArray>, col0: Int) {
            for (i in 0 until 8) {
                val a = plane[2 * i]
                val b = plane[2 * i + 1]
                for (j in 0 until 8) {
                    val x = col0 + 2 * j
                    block[i * 8 + j] = (a[x] + a[x + 1] + b[x] + b[x + 1]) * 0.25f
                }
            }
        }

        /** DCT, kwantisatie en Huffman van het blok in [block]. Geeft de nieuwe DC-voorspeller terug. */
        private fun codeBlock(div: FloatArray, prevDc: Int, dc: HuffCodes, ac: HuffCodes): Int {
            fdct(block)
            for (k in 0 until 64) {
                val nat = JpegTables.ZIGZAG[k]
                coef[k] = (block[nat] * div[nat] + 16384.5f).toInt() - 16384
            }
            val diff = coef[0] - prevDc
            val absDiff = if (diff < 0) -diff else diff
            val dcBits = 32 - Integer.numberOfLeadingZeros(absDiff)
            putBits(dc.code[dcBits], dc.size[dcBits])
            if (dcBits > 0) putBits(if (diff < 0) diff - 1 else diff, dcBits)
            var run = 0
            for (k in 1 until 64) {
                val v = coef[k]
                if (v == 0) { run++; continue }
                while (run > 15) { putBits(ac.code[0xF0], ac.size[0xF0]); run -= 16 }
                val a = if (v < 0) -v else v
                val nb = 32 - Integer.numberOfLeadingZeros(a)
                val sym = (run shl 4) or nb
                putBits(ac.code[sym], ac.size[sym])
                putBits(if (v < 0) v - 1 else v, nb)
                run = 0
            }
            if (run > 0) putBits(ac.code[0x00], ac.size[0x00])
            return coef[0]
        }

        private fun putBits(value: Int, count: Int) {
            if (count == 0) return
            acc = (acc shl count) or (value.toLong() and ((1L shl count) - 1))
            accBits += count
            while (accBits >= 8) {
                val b = ((acc shr (accBits - 8)) and 0xFF).toInt()
                accBits -= 8
                if (bufferLen > buffer.size - 2) buffer = buffer.copyOf(buffer.size * 2)
                buffer[bufferLen++] = b.toByte()
                if (b == 0xFF) buffer[bufferLen++] = 0
            }
            acc = acc and ((1L shl accBits) - 1)
        }
    }

    // ---- tabellen ----

    private class HuffCodes(counts: IntArray, symbols: IntArray) {
        val code = IntArray(256)
        val size = IntArray(256)
        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                for (i in 0 until counts[len - 1]) { code[symbols[k]] = c; size[symbols[k]] = len; c++; k++ }
                c = c shl 1
            }
        }
    }

    companion object {
        private val AAN = floatArrayOf(1.0f, 1.387039845f, 1.306562965f, 1.175875602f, 1.0f, 0.785694958f, 0.541196100f, 0.275899379f)

        /** libjpeg-kwaliteitsschaal: 50 geeft de basistabel, hoger is fijner. */
        fun scaledTable(base: IntArray, quality: Int): IntArray {
            val q = quality.coerceIn(1, 100)
            val scale = if (q < 50) 5000 / q else 200 - q * 2
            return IntArray(64) { ((base[it] * scale + 50) / 100).coerceIn(1, 255) }
        }

        private fun divisors(q: IntArray) = FloatArray(64) { 1f / (q[it] * AAN[it / 8] * AAN[it % 8] * 8f) }

        /** AAN-float-DCT in het blok zelf (zoals jfdctflt.c); de schaling zit in de kwantisatiedelers. */
        internal fun fdct(d: FloatArray) {
            for (pass in 0 until 2) {
                val step = if (pass == 0) 1 else 8
                val lane = if (pass == 0) 8 else 1
                for (l in 0 until 8) {
                    val p = l * lane
                    val tmp0 = d[p] + d[p + 7 * step]
                    val tmp7 = d[p] - d[p + 7 * step]
                    val tmp1 = d[p + step] + d[p + 6 * step]
                    val tmp6 = d[p + step] - d[p + 6 * step]
                    val tmp2 = d[p + 2 * step] + d[p + 5 * step]
                    val tmp5 = d[p + 2 * step] - d[p + 5 * step]
                    val tmp3 = d[p + 3 * step] + d[p + 4 * step]
                    val tmp4 = d[p + 3 * step] - d[p + 4 * step]
                    var tmp10 = tmp0 + tmp3
                    val tmp13 = tmp0 - tmp3
                    var tmp11 = tmp1 + tmp2
                    var tmp12 = tmp1 - tmp2
                    d[p] = tmp10 + tmp11
                    d[p + 4 * step] = tmp10 - tmp11
                    val z1 = (tmp12 + tmp13) * 0.707106781f
                    d[p + 2 * step] = tmp13 + z1
                    d[p + 6 * step] = tmp13 - z1
                    tmp10 = tmp4 + tmp5
                    tmp11 = tmp5 + tmp6
                    tmp12 = tmp6 + tmp7
                    val z5 = (tmp10 - tmp12) * 0.382683433f
                    val z2 = 0.541196100f * tmp10 + z5
                    val z4 = 1.306562965f * tmp12 + z5
                    val z3 = tmp11 * 0.707106781f
                    val z11 = tmp7 + z3
                    val z13 = tmp7 - z3
                    d[p + 5 * step] = z13 + z2
                    d[p + 3 * step] = z13 - z2
                    d[p + step] = z11 + z4
                    d[p + 7 * step] = z11 - z4
                }
            }
        }
    }
}
