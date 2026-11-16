package nl.markmaaktmedia.keeper.convert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class OutputMetadataTest {
    private fun segment(marker: Int, payload: ByteArray) = JpegSegments.app(marker, payload)
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test fun keepsTheExifVerbatimAndTheIccProfileAndNothingElse() {
        val exif = ascii("Exif\u0000\u0000") + ByteArray(300) { (it * 7).toByte() }
        val icc = ByteArray(1_000) { it.toByte() }
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            segment(0xE1, exif) +
            segment(0xE1, ascii(JpegHead.XMP_ID) + ascii("<x:xmpmeta hdrgm:Version=\"1.0\"/>")) +
            segment(0xE2, ascii("MPF\u0000") + ByteArray(40)) +
            JpegSegments.icc(icc).reduce { a, b -> a + b } +
            segment(0xE4, ascii("QTI debug") + ByteArray(20)) +
            // Een korte scan zodat de kop eindigt.
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
        val head = JpegParser.readHead(ByteArrayInputStream(bytes))!!

        val out = OutputMetadata.segments(head)
        // Eerst de Exif, dan het profiel.
        assertEquals(1 + 1, out.size)
        assertArrayEquals(segment(0xE1, exif), out[0])
        assertArrayEquals(segment(0xE2, ascii("ICC_PROFILE\u0000") + byteArrayOf(1, 1) + icc), out[1])
        // Niets van de HDR-kant blijft over.
        val all = out.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertTrue("hdrgm" !in all && "MPF" !in all && "QTI" !in all)
    }

    @Test fun aJpegWithoutMetadataGivesNoSegments() {
        val head = JpegParser.readHead(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2)))!!
        assertTrue(OutputMetadata.segments(head).isEmpty())
    }
}
