package nl.markmaaktmedia.keeper.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class DngPreviewTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    /** Een kleine TIFF met één IFD: [entries] zijn (tag, type, waarde). De JPEG staat achter de IFD. */
    private fun tiff(order: ByteOrder, entries: (Long) -> List<Triple<Int, Int, Long>>, payload: ByteArray = jpeg): ByteArray {
        val ifdAt = 8
        val list = entries(0)
        val dataAt = ifdAt + 2 + list.size * 12 + 4
        val resolved = entries(dataAt.toLong())
        val buf = ByteBuffer.allocate(dataAt + payload.size).order(order)
        buf.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte()).put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        buf.putShort(42).putInt(ifdAt)
        buf.putShort(resolved.size.toShort())
        for ((tag, type, value) in resolved) {
            buf.putShort(tag.toShort()).putShort(type.toShort()).putInt(1)
            if (type == 3) { buf.putShort(value.toShort()); buf.putShort(0) } else buf.putInt(value.toInt())
        }
        buf.putInt(0)
        buf.put(payload)
        return buf.array()
    }

    private fun find(bytes: ByteArray): DngPreview.Range? {
        val file = File.createTempFile("dng", ".bin").also { it.deleteOnExit(); it.writeBytes(bytes) }
        return FileChannel.open(file.toPath(), StandardOpenOption.READ).use { DngPreview.find(it) }
    }

    private fun stripped(at: Long, length: Long, compression: Int = 7) =
        listOf(Triple(259, 3, compression.toLong()), Triple(273, 4, at), Triple(279, 4, length))

    @Test fun findsTheStripInBothByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val r = find(tiff(order, { at -> stripped(at, jpeg.size.toLong()) }))
            assertNotNull(r); assertEquals(jpeg.size.toLong(), r!!.length); assertEquals(8L + 2 + 3 * 12 + 4, r.offset)
        }
    }

    @Test fun findsAnOldStyleJpegInterchangeFormat() {
        val r = find(tiff(ByteOrder.LITTLE_ENDIAN, { at -> listOf(Triple(513, 4, at), Triple(514, 4, jpeg.size.toLong())) }))
        assertNotNull(r); assertEquals(jpeg.size.toLong(), r!!.length)
    }

    @Test fun refusesWhatIsNotAJpegOrDoesNotFit() {
        // Geen JPEG-kop op de plek.
        assertNull(find(tiff(ByteOrder.LITTLE_ENDIAN, { at -> stripped(at, jpeg.size.toLong()) }, payload = ByteArray(jpeg.size))))
        // Strook voorbij het bestand.
        assertNull(find(tiff(ByteOrder.LITTLE_ENDIAN, { at -> stripped(at, 10_000) })))
        // Ongecomprimeerd (de ruwe data zelf): geen preview.
        assertNull(find(tiff(ByteOrder.LITTLE_ENDIAN, { at -> stripped(at, jpeg.size.toLong(), compression = 1) })))
    }

    @Test fun refusesAFileThatIsNoTiff() {
        assertNull(find(jpeg + ByteArray(64)))
        assertNull(find(ByteArray(3)))
    }
}
