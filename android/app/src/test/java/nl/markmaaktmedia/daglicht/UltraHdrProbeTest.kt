package nl.markmaaktmedia.daglicht

import nl.markmaaktmedia.daglicht.scan.UltraHdrProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UltraHdrProbeTest {
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(marker)
        out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** Een MPF-kop (little endian) met twee beelden; het tweede begint [secondOffset] na de kop en is [secondSize] groot. */
    private fun mpfPayload(secondOffset: Int, secondSize: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(4 + 8 + 2 + 12 + 4 + 32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("MPF\u0000II*\u0000".toByteArray(Charsets.ISO_8859_1)); b.putInt(8)
        b.putShort(1); b.putShort(0xB002.toShort()); b.putShort(7); b.putInt(32); b.putInt(26); b.putInt(0)
        b.putInt(0x030000); b.putInt(1000); b.putInt(0); b.putShort(0); b.putShort(0)
        b.putInt(0); b.putInt(secondSize); b.putInt(secondOffset); b.putShort(0); b.putShort(0)
        return b.array()
    }

    private fun jpeg(xmp: String?, mpf: Boolean, second: Pair<Int, Int> = 5000 to 200): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (xmp != null) out.write(segment(0xE1, ("http://ns.adobe.com/xap/1.0/\u0000$xmp").toByteArray(Charsets.ISO_8859_1)))
        if (mpf) out.write(segment(0xE2, mpfPayload(second.first, second.second)))
        // SOF0: precisie 8, hoogte 3064, breedte 4080
        out.write(segment(0xC0, byteArrayOf(8, 0x0B, 0xF8.toByte(), 0x0F, 0xF0.toByte(), 3, 1, 0x22, 0)))
        out.write(segment(0xDA, byteArrayOf(0)))
        return out.toByteArray()
    }

    @Test fun findsGainMapAndSize() {
        val p = UltraHdrProbe.probe(ByteArrayInputStream(jpeg("<x hdrgm:Version=\"1.0\"/>", mpf = true)))
        assertTrue(p.hasGainMap); assertEquals(4080, p.width); assertEquals(3064, p.height)
    }

    @Test fun gainMapMustReallyBeInTheFile() {
        val file = jpeg("<x hdrgm:Version=\"1.0\"/>", mpf = true)  // het tweede beeld eindigt ruim na het einde van dit bestand
        assertTrue(UltraHdrProbe.probe(ByteArrayInputStream(file), fileSize = 5_300).hasGainMap)
        assertFalse(UltraHdrProbe.probe(ByteArrayInputStream(file), fileSize = 1_000).hasGainMap)
    }

    @Test fun needsBothMpfAndHdrgm() {
        assertFalse(UltraHdrProbe.probe(ByteArrayInputStream(jpeg("<x hdrgm:Version=\"1.0\"/>", mpf = false))).hasGainMap)
        assertFalse(UltraHdrProbe.probe(ByteArrayInputStream(jpeg(null, mpf = true))).hasGainMap)
        assertFalse(UltraHdrProbe.probe(ByteArrayInputStream(jpeg(null, mpf = false))).hasGainMap)
    }

    @Test fun garbageIsNotAGainMap() {
        assertFalse(UltraHdrProbe.probe(ByteArrayInputStream(byteArrayOf(1, 2, 3))).hasGainMap)
        assertFalse(UltraHdrProbe.probe(ByteArrayInputStream(ByteArray(0))).hasGainMap)
    }
}
