package nl.markmaaktmedia.keeper.lightroom

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** De verwachte bestanden komen uit `prototype/maak_dng_fixtures.py` (Python-versie van dezelfde aanpak); ze moeten byte voor byte gelijk zijn. */
class DngXmpTest {
    private val description = "<rdf:Description rdf:about=\"\" xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\" " +
        "crs:Version=\"18.4\" crs:HasSettings=\"True\" crs:Saturation=\"-100\"/>"

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/$name")!!.readBytes()

    private fun check(name: String) {
        val src = File.createTempFile("keeper-src", ".dng").apply { deleteOnExit(); writeBytes(resource("$name.dng")) }
        val dst = File.createTempFile("keeper-dst", ".dng").apply { deleteOnExit() }
        DngXmp.embed(src, dst, description)
        assertArrayEquals("$name komt niet overeen met de Python-uitvoer", resource("$name-verwacht.dng"), dst.readBytes())
        val xmp = DngXmp.readXmp(dst)!!
        assertTrue(xmp.contains("crs:Saturation=\"-100\""))
    }

    @Test fun `zonder bestaande xmp komt er een nieuwe ifd0 achteraan`() = check("tiff-zonder-xmp")
    @Test fun `bestaande xmp blijft staan en krijgt de beschrijving erbij`() {
        check("tiff-met-xmp")
        val src = File.createTempFile("keeper-src", ".dng").apply { deleteOnExit(); writeBytes(resource("tiff-met-xmp.dng")) }
        val dst = File.createTempFile("keeper-dst", ".dng").apply { deleteOnExit() }
        DngXmp.embed(src, dst, description)
        val xmp = DngXmp.readXmp(dst)!!
        assertTrue("hdrgm blijft behouden", xmp.contains("hdrgm:Version"))
    }
    @Test fun `big-endian tiff werkt ook`() = check("tiff-big-endian")

    @Test fun `bron blijft ongewijzigd`() {
        val bytes = resource("tiff-zonder-xmp.dng")
        val src = File.createTempFile("keeper-src", ".dng").apply { deleteOnExit(); writeBytes(bytes) }
        DngXmp.embed(src, File.createTempFile("keeper-dst", ".dng").apply { deleteOnExit() }, description)
        assertArrayEquals(bytes, src.readBytes())
        assertNull(DngXmp.readXmp(src))
    }

    @Test fun `jpeg en tiff aan de kop herkennen`() {
        assertTrue(DngXmp.isTiff(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)))
        assertTrue(DngXmp.isTiff(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)))
        assertFalse(DngXmp.isTiff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())))
        assertFalse(DngXmp.isTiff(byteArrayOf(1, 2)))
    }

    @Test fun `een jpeg met de naam dng wordt geweigerd`() {
        val f = File.createTempFile("keeper-jpg", ".dng").apply { deleteOnExit(); writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 0, 0, 0, 0, 0)) }
        val e = runCatching { DngXmp.embed(f, File.createTempFile("keeper-dst", ".dng").apply { deleteOnExit() }, description) }.exceptionOrNull()
        assertTrue(e is DngXmp.NotATiffException)
    }

    @Test fun `merge zet de beschrijving voor het einde van rdf`() {
        val merged = DngXmp.merge("<x:xmpmeta><rdf:RDF><rdf:Description a=\"1\"/></rdf:RDF></x:xmpmeta>", "<rdf:Description b=\"2\"/>")
        assertEquals("<x:xmpmeta><rdf:RDF><rdf:Description a=\"1\"/><rdf:Description b=\"2\"/>\n </rdf:RDF></x:xmpmeta>", merged)
    }
}
