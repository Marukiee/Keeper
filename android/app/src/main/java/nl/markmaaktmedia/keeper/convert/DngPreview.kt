package nl.markmaaktmedia.keeper.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * De camera-versie van een DNG van de Find X9 Ultra: IFD0 bevat een JPEG van het volledige formaat (7356 x 5516 bij 40 MP), met het
 * camerafilter, de Exif, het ICC-profiel en een eigen gain map, dus een Ultra HDR-bestand dat hetzelfde is als een gewone camera-JPG.
 * Hier alleen het opsporen van die JPEG: waar hij begint en hoe lang hij is.
 */
object DngPreview {
    class Range(val offset: Long, val length: Long)

    private const val TAG_COMPRESSION = 259
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_STRIP_BYTES = 279
    private const val TAG_JPEG_OFFSET = 513
    private const val TAG_JPEG_LENGTH = 514

    /** De JPEG in IFD0 van [file] (klassiek TIFF), of null als er geen is of het geen DNG is. Controleert dat er een JPEG-kop staat. */
    fun find(file: FileChannel): Range? {
        val head = read(file, 0, 8) ?: return null
        val order = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val hb = ByteBuffer.wrap(head).order(order)
        if (hb.getShort(2).toInt() != 42) return null
        val ifd = hb.getInt(4).toLong() and 0xFFFFFFFFL
        val countBytes = read(file, ifd, 2) ?: return null
        val n = ByteBuffer.wrap(countBytes).order(order).short.toInt() and 0xFFFF
        val raw = read(file, ifd + 2, n * 12) ?: return null
        val bb = ByteBuffer.wrap(raw).order(order)
        val values = HashMap<Int, LongArray>()
        for (i in 0 until n) {
            val at = i * 12
            val tag = bb.getShort(at).toInt() and 0xFFFF
            val type = bb.getShort(at + 2).toInt() and 0xFFFF
            val count = bb.getInt(at + 4).toLong() and 0xFFFFFFFFL
            // Alleen enkele waarden (één strook, één JPEG): meerdere stroken geven we niet door.
            if (count != 1L) continue
            val v = when (type) {
                3 -> (bb.getShort(at + 8).toLong() and 0xFFFF)
                4 -> (bb.getInt(at + 8).toLong() and 0xFFFFFFFFL)
                else -> continue
            }
            values[tag] = longArrayOf(v)
        }
        val compression = values[TAG_COMPRESSION]?.get(0)
        val start = values[TAG_STRIP_OFFSETS]?.get(0) ?: values[TAG_JPEG_OFFSET]?.get(0) ?: return null
        val length = values[TAG_STRIP_BYTES]?.get(0) ?: values[TAG_JPEG_LENGTH]?.get(0) ?: return null
        if (compression != null && compression != 7L && compression != 6L) return null
        if (length < 4 || start + length > file.size()) return null
        val soi = read(file, start, 2) ?: return null
        if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) return null
        return Range(start, length)
    }

    private fun read(file: FileChannel, at: Long, count: Int): ByteArray? {
        if (at < 0 || at + count > file.size()) return null
        val buf = ByteBuffer.allocate(count)
        var pos = at
        while (buf.hasRemaining()) { val r = file.read(buf, pos); if (r < 0) return null; pos += r }
        return buf.array()
    }
}
