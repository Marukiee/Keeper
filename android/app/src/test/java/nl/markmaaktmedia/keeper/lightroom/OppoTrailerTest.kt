package nl.markmaaktmedia.keeper.lightroom

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OppoTrailerTest {
    /** Bouwt een bestand zoals de camera: [jpeg][blokken][8 nullen][json][0x00]["jxrs"][lengte]. De offsets tellen terug vanaf de `[`. */
    private fun fakeJpeg(blocks: List<Pair<String, ByteArray>>): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5))
        val starts = ArrayList<Long>()
        for ((_, data) in blocks) { starts += out.size().toLong(); out.write(data) }
        val padding = ByteArray(8)
        // json bouwen met offsets: dirStart = out.size() + 8 (nullen), offset = dirStart - start
        val dirStart = out.size().toLong() + padding.size
        val json = blocks.indices.joinToString(",", "[", "]") { i ->
            """{"length":${blocks[i].second.size},"name":"${blocks[i].first}","offset":${dirStart - starts[i]},"version":1}"""
        }
        out.write(padding); out.write(json.toByteArray()); out.write(0)
        val n = padding.size + json.length + 1
        out.write("jxrs".toByteArray())
        out.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
        return File.createTempFile("keeper-oppo", ".jpg").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    private fun presetBlock(name: String) = ByteArray(496).also { b ->
        b[0] = 0; b[1] = 0; b[2] = 0x80.toByte(); b[3] = 0x3f; b[4] = 100
        name.toByteArray().copyInto(b, 236)
    }

    private fun read(f: File) = RandomAccessFile(f, "r").use { OppoTrailer.presetName(it.channel) }

    @Test fun `leest de naam uit het presetblok`() {
        for (name in listOf("Warm flash", "Fresh", "Vintage", "Standard")) {
            val f = fakeJpeg(listOf("capture.mode" to "master\u0000".toByteArray(), "master.mode.preset.info" to presetBlock(name), "private.emptyspace" to ByteArray(4)))
            assertEquals(name, read(f))
        }
    }

    @Test fun `zonder presetblok geen naam`() {
        assertNull(read(fakeJpeg(listOf("capture.mode" to "master\u0000".toByteArray(), "private.emptyspace" to ByteArray(4)))))
    }

    @Test fun `bestand zonder trailer geeft null`() {
        assertNull(read(File.createTempFile("keeper-plain", ".jpg").apply { deleteOnExit(); writeBytes(ByteArray(500) { 7 }) }))
        assertNull(read(File.createTempFile("keeper-tiny", ".jpg").apply { deleteOnExit(); writeBytes(ByteArray(5)) }))
    }

    @Test fun `leeg presetblok geeft null`() {
        assertNull(read(fakeJpeg(listOf("master.mode.preset.info" to ByteArray(496)))))
    }
}

/** De staart van echte camera-JPG's van de Find X9 Ultra (alleen de laatste kilobytes: index, presetblok en wat ernaast ligt). */
class OppoTrailerRealTest {
    private fun read(name: String): String? {
        val bytes = javaClass.getResourceAsStream("/$name")!!.readBytes()
        val f = File.createTempFile("keeper-real", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
        return RandomAccessFile(f, "r").use { OppoTrailer.presetName(it.channel) }
    }

    @Test fun `echte trailers geven de presetnaam`() {
        assertEquals("Fresh", read("oppo-trailer-fresh.bin"))
        assertEquals("Warm flash", read("oppo-trailer-warm-flash.bin"))
        assertEquals("Vintage", read("oppo-trailer-vintage.bin"))
    }
}
