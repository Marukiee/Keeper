package nl.markmaaktmedia.keeper.scan

import java.io.InputStream

/**
 * Leest alleen de kopsegmenten van een JPEG, tot de eerste scan, en zegt of er een gain map in zit.
 * Een Ultra HDR-bestand heeft een MPF-segment (APP2) en hdrgm in de XMP (APP1) van het hoofdbeeld.
 * Meer dan een paar tientallen kB wordt nooit gelezen, dus honderden foto's scannen blijft snel.
 */
object UltraHdrProbe {
    private const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val MAX_SEGMENT = 256 * 1024

    /**
     * [fileSize] is nodig om te zien of het tweede beeld van de MPF-kop er echt is: een bestand dat alleen het
     * hoofdbeeld bevat (gain map eraf geknipt) heeft de kop nog wel en moet niet als HDR tellen.
     */
    fun probe(input: InputStream, fileSize: Long = Long.MAX_VALUE): Probe {
        val ins = Counting(input.buffered(64 * 1024))
        if (ins.read() != 0xFF || ins.read() != 0xD8) return Probe(hasGainMap = false)
        var hdrgm = false
        var mpf = false
        var secondImageEnd = -1L
        var width = 0
        var height = 0
        while (true) {
            var b = ins.read()
            while (b != 0xFF && b != -1) b = ins.read()
            if (b == -1) break
            var marker = ins.read()
            while (marker == 0xFF) marker = ins.read()
            if (marker == -1 || marker == 0xDA || marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD7) continue // markers zonder lengte
            val len = (ins.read() shl 8) or ins.read()
            if (len < 2) break
            val size = len - 2
            if (size > MAX_SEGMENT) { ins.skipFully(size); continue }
            val payloadStart = ins.pos
            val payload = ByteArray(size)
            if (!ins.readFully(payload)) break
            when {
                marker == 0xE1 && payload.startsWithText(XMP_ID) ->
                    if (String(payload, Charsets.ISO_8859_1).contains("hdrgm:")) hdrgm = true
                marker == 0xE2 && payload.startsWithText("MPF\u0000") -> {
                    mpf = true
                    secondImageEnd = mpfSecondImageEnd(payload, payloadStart) ?: -1L
                }
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && size >= 5 -> {
                    height = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
                    width = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
                }
            }
        }
        return Probe(hasGainMap = hdrgm && mpf && secondImageEnd in 1..fileSize, hasMpf = mpf, width = width, height = height)
    }

    /** Waar het tweede beeld van de MPF-directory ophoudt, als absolute positie in het bestand. */
    private fun mpfSecondImageEnd(p: ByteArray, payloadStart: Long): Long? {
        val tiff = 4
        if (p.size < tiff + 8) return null
        val little = p[tiff] == 'I'.code.toByte()
        fun u16(o: Int): Int = if (o + 2 > p.size) -1 else if (little) (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
            else ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
        fun u32(o: Int): Long = if (o + 4 > p.size) -1 else if (little)
            (0..3).fold(0L) { acc, i -> acc or ((p[o + i].toLong() and 0xFF) shl (8 * i)) }
        else (0..3).fold(0L) { acc, i -> (acc shl 8) or (p[o + i].toLong() and 0xFF) }
        val ifd = u32(tiff + 4).toInt()
        val count = u16(tiff + ifd)
        for (i in 0 until count) {
            val e = tiff + ifd + 2 + 12 * i
            if (u16(e) != 0xB002) continue
            val images = u32(e + 4) / 16
            val entries = tiff + u32(e + 8).toInt()
            if (images < 2 || entries < 0) return null
            val second = entries + 16
            val size = u32(second + 4)
            val offset = u32(second + 8)
            if (size <= 0 || offset <= 0) return null
            return payloadStart + tiff + offset + size
        }
        return null
    }

    /** Telt de gelezen bytes, zodat een segment een absolute positie in het bestand krijgt. */
    private class Counting(input: InputStream) : java.io.FilterInputStream(input) {
        var pos = 0L
            private set
        override fun read(): Int = super.read().also { if (it >= 0) pos++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) pos += it }
        override fun skip(n: Long): Long = super.skip(n).also { pos += it }
    }

    private fun ByteArray.startsWithText(text: String): Boolean =
        size >= text.length && text.indices.all { this[it].toInt() == text[it].code }

    private fun InputStream.readFully(target: ByteArray): Boolean {
        var read = 0
        while (read < target.size) {
            val n = read(target, read, target.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun InputStream.skipFully(count: Int) {
        var left = count.toLong()
        while (left > 0) {
            val n = skip(left)
            if (n <= 0) { if (read() < 0) return else left-- } else left -= n
        }
    }
}
