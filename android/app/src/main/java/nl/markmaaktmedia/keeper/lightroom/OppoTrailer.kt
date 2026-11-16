package nl.markmaaktmedia.keeper.lightroom

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Leest uit de Oppo-trailer achter een camera-JPG welke preset (filter) gebruikt is. Gemeten op de Find X9 Ultra:
 * het bestand eindigt op `[JSON-index][0x00]["jxrs"][lengte u32 LE]`; de index noemt blokken, de offsets tellen terug vanaf de `[` van de index.
 * Het blok `master.mode.preset.info` (496 bytes) heeft de naam als ASCII op byte 236 ("Warm flash", "Fresh", "Vintage", "Standard").
 * Zonder preset ontbreekt het blok. Een DNG heeft dit niet (zie CLAUDE.md), alleen JPG's.
 */
object OppoTrailer {
    private const val NAME_OFFSET = 236
    private const val NAME_MAX = 40
    private const val BLOCK = "master.mode.preset.info"

    /** Naam van de preset, of null als er geen (leesbaar) blok is. [f] mag een groot bestand zijn: alleen de staart wordt gelezen. */
    fun presetName(f: FileChannel): String? {
        val size = f.size()
        if (size < 16) return null
        val tail = ByteArray(8)
        f.readAt(size - 8, tail)
        if (tail[0] != 'j'.code.toByte() || tail[1] != 'x'.code.toByte() || tail[2] != 'r'.code.toByte() || tail[3] != 's'.code.toByte()) return null
        val n = (tail[4].toLong() and 0xFF) or ((tail[5].toLong() and 0xFF) shl 8) or ((tail[6].toLong() and 0xFF) shl 16) or ((tail[7].toLong() and 0xFF) shl 24)
        val end = size - 8
        if (n <= 0 || n > end || n > 1_000_000) return null
        val dir = ByteArray(n.toInt())
        f.readAt(end - n, dir)
        val bracket = dir.indexOf('['.code.toByte())
        if (bracket < 0) return null
        var jsonEnd = dir.size
        while (jsonEnd > bracket && dir[jsonEnd - 1] == 0.toByte()) jsonEnd--
        val json = String(dir, bracket, jsonEnd - bracket, Charsets.UTF_8)
        val dirStart = end - n + bracket
        // De index is een platte lijst van objecten; een regex is genoeg en werkt ook in JVM-tests zonder org.json.
        for (obj in Regex("\\{[^{}]*\\}").findAll(json)) {
            val text = obj.value
            if (Regex("\"name\"\\s*:\\s*\"" + Regex.escape(BLOCK) + "\"").find(text) == null) continue
            val length = Regex("\"length\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val offset = Regex("\"offset\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val start = dirStart - offset
            if (length < NAME_OFFSET + 2 || start < 0 || start + length > size) return null
            val block = ByteArray(length.toInt())
            f.readAt(start, block)
            return asciiName(block)
        }
        return null
    }

    private fun FileChannel.readAt(position: Long, into: ByteArray) {
        val bb = ByteBuffer.wrap(into)
        var at = position
        while (bb.hasRemaining()) {
            val n = read(bb, at)
            if (n < 0) throw EOFException()
            at += n
        }
    }

    internal fun asciiName(block: ByteArray): String? {
        var i = NAME_OFFSET
        val sb = StringBuilder()
        while (i < block.size && i < NAME_OFFSET + NAME_MAX) {
            val c = block[i].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) break
            sb.append(c.toChar()); i++
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }
}
