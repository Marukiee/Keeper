package nl.markmaaktmedia.keeper.convert

import java.io.InputStream

/** Eén segment uit de kop van een JPEG. `payload` is null als het te groot was om te bewaren (de plek en lengte kennen we wel). */
class JpegSegment(val marker: Int, val offset: Long, val length: Int, val payload: ByteArray?) {
    fun startsWith(text: String): Boolean {
        val p = payload ?: return false
        if (p.size < text.length) return false
        for (i in text.indices) if (p[i] != text[i].code.toByte()) return false
        return true
    }
}

/** De kop van een JPEG tot de eerste scan: segmenten, afmetingen en waar de scan begint. */
class JpegHead(val segments: List<JpegSegment>, val width: Int, val height: Int, val components: Int, val sosOffset: Long) {
    /** De gewone Exif-TIFF zonder de kop "Exif\0\0", of null. */
    fun exifTiff(): ByteArray? = segments.firstOrNull { it.marker == APP1 && it.startsWith("Exif\u0000\u0000") }
        ?.payload?.let { it.copyOfRange(6, it.size) }

    /** XMP-pakketten (er kan een extended XMP bij zitten; wij lezen alleen het gewone pakket). */
    fun xmp(): String? = segments.firstOrNull { it.marker == APP1 && it.startsWith(XMP_ID) }
        ?.payload?.let { String(it, XMP_ID.length, it.size - XMP_ID.length, Charsets.UTF_8) }

    /** Het ICC-profiel, uit één of meer APP2-stukken in volgorde aan elkaar gezet. */
    fun icc(): ByteArray? {
        val parts = segments.filter { it.marker == APP2 && it.startsWith("ICC_PROFILE\u0000") && (it.payload?.size ?: 0) > 14 }
            .sortedBy { it.payload!![12].toInt() and 0xFF }
        if (parts.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(it.payload!!, 14, it.payload.size - 14) }
        return out.toByteArray()
    }

    /** De afbeeldingen uit de MPF-directory als (begin, lengte) in het bestand, het eerste beeld eerst. Null zonder MPF. */
    fun mpfImages(): List<Pair<Long, Long>>? {
        val seg = segments.firstOrNull { it.marker == APP2 && it.startsWith("MPF\u0000") } ?: return null
        val p = seg.payload ?: return null
        val tiffAt = 4
        if (p.size < tiffAt + 8) return null
        val little = p[tiffAt] == 'I'.code.toByte()
        fun u16(o: Int): Int = if (o < 0 || o + 2 > p.size) -1 else if (little) (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
            else ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
        fun u32(o: Int): Long = if (o < 0 || o + 4 > p.size) -1 else if (little)
            (0..3).fold(0L) { acc, i -> acc or ((p[o + i].toLong() and 0xFF) shl (8 * i)) }
        else (0..3).fold(0L) { acc, i -> (acc shl 8) or (p[o + i].toLong() and 0xFF) }
        val ifd = u32(tiffAt + 4).toInt()
        val count = u16(tiffAt + ifd)
        // De offsets in de directory tellen vanaf de TIFF-kop; die staat 4 bytes na het begin van de payload (die begint 4 na het segment).
        val tiffAbs = seg.offset + 4 + tiffAt
        for (i in 0 until count) {
            val e = tiffAt + ifd + 2 + 12 * i
            if (u16(e) != 0xB002) continue
            val n = (u32(e + 4) / 16).toInt()
            val entries = tiffAt + u32(e + 8).toInt()
            val out = ArrayList<Pair<Long, Long>>()
            for (k in 0 until n) {
                val size = u32(entries + 16 * k + 4)
                val off = u32(entries + 16 * k + 8)
                if (size <= 0 || off < 0) return null
                out += (if (k == 0) 0L else tiffAbs + off) to size
            }
            return out
        }
        return null
    }

    companion object {
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"
    }
}

object JpegParser {
    /** Leest tot de eerste scan. Segmenten groter dan [keepLimit] worden overgeslagen (alleen plek en lengte). Null als dit geen JPEG is. */
    fun readHead(input: InputStream, keepLimit: Int = 1 shl 20): JpegHead? {
        val ins = input.buffered(64 * 1024)
        var pos = 0L
        fun rd(): Int = ins.read().also { if (it >= 0) pos++ }
        if (rd() != 0xFF || rd() != 0xD8) return null
        val segments = ArrayList<JpegSegment>()
        var width = 0
        var height = 0
        var comps = 0
        var sos = -1L
        while (true) {
            var b = rd()
            while (b != 0xFF && b != -1) b = rd()
            if (b == -1) break
            val at = pos - 1
            var marker = rd()
            while (marker == 0xFF) marker = rd()
            if (marker == -1 || marker == 0xD9) break
            if (marker == 0xDA) { sos = at; break }
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            val len = (rd() shl 8) or rd()
            if (len < 2) break
            val size = len - 2
            if (size > keepLimit) {
                var left = size.toLong()
                while (left > 0) { val s = ins.skip(left); if (s <= 0) break; left -= s; pos += s }
                segments += JpegSegment(marker, at, size, null)
                continue
            }
            val payload = ByteArray(size)
            var got = 0
            while (got < size) { val r = ins.read(payload, got, size - got); if (r < 0) return null; got += r }
            pos += size
            segments += JpegSegment(marker, at, size, payload)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && size >= 6) {
                height = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
                width = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
                comps = payload[5].toInt() and 0xFF
            }
        }
        return JpegHead(segments, width, height, comps, sos)
    }
}
