package nl.markmaaktmedia.keeper.convert

/**
 * Zet de afmetingen in de Exif van een foto die op andere afmetingen is bewaard: ImageWidth en ImageLength (0x0100, 0x0101) in IFD0 en
 * PixelXDimension en PixelYDimension (0xA002, 0xA003) in de Exif-IFD. De rest van de Exif (datum, GPS, lens, MakerNote) blijft zoals de camera het schreef,
 * en de lengte verandert niet: een waarde wordt op zijn plaats overschreven. Staat een tag er niet in of past de waarde niet, dan blijft hij zoals hij was.
 */
internal object ExifDimensions {
    /** [payload] is de inhoud van het APP1-segment, met de kop "Exif\0\0" voorop. Geeft een kopie terug. */
    fun patch(payload: ByteArray, width: Int, height: Int): ByteArray {
        val out = payload.copyOf()
        val tiff = 6
        if (out.size < tiff + 8) return out
        val little = when {
            out[tiff] == 'I'.code.toByte() && out[tiff + 1] == 'I'.code.toByte() -> true
            out[tiff] == 'M'.code.toByte() && out[tiff + 1] == 'M'.code.toByte() -> false
            else -> return out
        }
        fun u16(p: Int) = if (little) (out[p].toInt() and 0xFF) or ((out[p + 1].toInt() and 0xFF) shl 8) else ((out[p].toInt() and 0xFF) shl 8) or (out[p + 1].toInt() and 0xFF)
        fun u32(p: Int): Long = if (little) (u16(p).toLong() or (u16(p + 2).toLong() shl 16)) else ((u16(p).toLong() shl 16) or u16(p + 2).toLong())
        fun put16(p: Int, v: Int) { if (little) { out[p] = v.toByte(); out[p + 1] = (v shr 8).toByte() } else { out[p] = (v shr 8).toByte(); out[p + 1] = v.toByte() } }
        fun put32(p: Int, v: Int) { if (little) { put16(p, v and 0xFFFF); put16(p + 2, v ushr 16) } else { put16(p, v ushr 16); put16(p + 2, v and 0xFFFF) } }

        fun set(entry: Int, value: Int) {
            val type = u16(entry + 2)
            if (u32(entry + 4) != 1L) return
            when (type) {
                3 -> if (value <= 0xFFFF) { put16(entry + 8, value); put16(entry + 10, 0) }
                4 -> put32(entry + 8, value)
            }
        }

        /** Loopt de IFD op [offset] (vanaf de TIFF-kop) langs en geeft de plek van de Exif-IFD-aanwijzer terug, of -1. */
        fun walk(offset: Long, tags: Map<Int, Int>): Long {
            val start = (tiff + offset).toInt()
            if (offset < 8 || start < 0 || start + 2 > out.size) return -1
            val count = u16(start)
            var exifPointer = -1L
            for (i in 0 until count) {
                val e = start + 2 + i * 12
                if (e + 12 > out.size) break
                val tag = u16(e)
                if (tag == 0x8769) exifPointer = u32(e + 8)
                tags[tag]?.let { set(e, it) }
            }
            return exifPointer
        }

        val ifd0 = u32(tiff + 4)
        val exifIfd = walk(ifd0, mapOf(0x0100 to width, 0x0101 to height))
        if (exifIfd > 0) walk(exifIfd, mapOf(0xA002 to width, 0xA003 to height))
        return out
    }
}
