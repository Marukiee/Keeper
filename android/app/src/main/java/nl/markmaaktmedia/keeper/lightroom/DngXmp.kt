package nl.markmaaktmedia.keeper.lightroom

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zet instellingen voor Lightroom in de XMP van een DNG, zonder de rest van het bestand aan te raken: alleen bytes achteraan toevoegen.
 * Dezelfde aanpak als `prototype/dng_xmp.py`; de test vergelijkt beide byte voor byte.
 *
 * Een DNG is klassiek TIFF (geen BigTIFF). De XMP staat in tag 700 van IFD0. Heeft het bestand die tag niet, dan komt er achteraan een kopie
 * van IFD0 mét de tag en wijst de kop daarnaartoe; alle andere offsets (SubIFD's, ruwe data, EXIF) zijn absoluut en blijven kloppen.
 */
object DngXmp {
    private const val XMP_TAG = 700
    private const val TYPE_BYTE = 1

    class NotATiffException(message: String) : Exception(message)

    /** Is dit bestand een TIFF/DNG? Aan de kop herkennen, nooit aan de naam: Oppo schrijft kort een JPEG met de naam `.dng`. */
    fun isTiff(head: ByteArray): Boolean =
        head.size >= 4 && ((head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 42.toByte() && head[3] == 0.toByte()) ||
            (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[2] == 0.toByte() && head[3] == 42.toByte()))

    /** De huidige XMP van [file], of null als er geen is. */
    fun readXmp(file: File): String? = RandomAccessFile(file, "r").use { f ->
        val h = readHeader(f)
        val ifd = readIfd(f, h)
        val e = ifd.entries.firstOrNull { it.tag == XMP_TAG } ?: return null
        val bytes = ByteArray(e.count.toInt())
        if (e.count <= 4) System.arraycopy(e.raw, 0, bytes, 0, e.count.toInt()) else { f.seek(e.valueOffset); f.readFully(bytes) }
        String(bytes, Charsets.UTF_8)
    }

    /** Kopieert [src] naar [dst] en voegt [description] (een `<rdf:Description .../>`) toe aan de XMP. Geeft [dst]. */
    fun embed(src: File, dst: File, description: String): File {
        src.copyTo(dst, overwrite = true)
        return patch(dst, description)
    }

    /** Voegt [description] toe aan de XMP van [dst], in dat bestand zelf. Geeft [dst]. */
    fun patch(dst: File, description: String): File {
        RandomAccessFile(dst, "rw").use { f ->
            val h = readHeader(f)
            val ifd = readIfd(f, h)
            val existing = ifd.entries.firstOrNull { it.tag == XMP_TAG }?.let { e ->
                val bytes = ByteArray(e.count.toInt())
                if (e.count <= 4) System.arraycopy(e.raw, 0, bytes, 0, e.count.toInt()) else { f.seek(e.valueOffset); f.readFully(bytes) }
                String(bytes, Charsets.UTF_8)
            }
            val packet = packet(merge(existing, description))

            var end = f.length()
            if (end % 2L == 1L) { f.seek(end); f.write(0); end++ }
            val dataOffset = end
            f.seek(end); f.write(packet)
            if (packet.size % 2 == 1) f.write(0)

            val entry = Entry(XMP_TAG, TYPE_BYTE, packet.size.toLong(), ByteBuffer.allocate(4).order(h.order).putInt(dataOffset.toInt()).array())
            val pos = ifd.entries.indexOfFirst { it.tag == XMP_TAG }
            if (pos >= 0) {
                // bestaande tag: alleen de ingang omleggen, de oude tekst blijft als dode bytes staan
                f.seek(ifd.offset + 2 + pos * 12L)
                f.write(entryBytes(entry, h.order))
            } else {
                val entries = (ifd.entries + entry).sortedBy { it.tag }
                val newIfd = f.length()
                f.seek(newIfd)
                f.write(ByteBuffer.allocate(2).order(h.order).putShort(entries.size.toShort()).array())
                for (e in entries) f.write(entryBytes(e, h.order))
                f.write(ifd.next)
                f.seek(4)
                f.write(ByteBuffer.allocate(4).order(h.order).putInt(newIfd.toInt()).array())
            }
        }
        return dst
    }

    internal fun merge(existing: String?, description: String): String {
        val desc = description.trim()
        if (existing.isNullOrBlank()) {
            return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Keeper\">\n <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                desc + "\n </rdf:RDF>\n</x:xmpmeta>"
        }
        val body = existing.replace(Regex("<\\?xpacket[^>]*\\?>"), "").trim()
        val i = body.lastIndexOf("</rdf:RDF>")
        if (i < 0) throw IllegalArgumentException("bestaande XMP heeft geen rdf:RDF")
        return body.substring(0, i) + desc + "\n " + body.substring(i)
    }

    internal fun packet(xml: String): ByteArray =
        ("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" + xml.trim() + "\n<?xpacket end=\"w\"?>").toByteArray(Charsets.UTF_8)

    // ---- TIFF ----

    private class Header(val order: ByteOrder, val ifdOffset: Long)

    private class Entry(val tag: Int, val type: Int, val count: Long, val raw: ByteArray) {
        /** Offset naar de gegevens, voor ingangen waarvan de waarde niet in de 4 bytes past. */
        fun valueOffsetOf(order: ByteOrder): Long = ByteBuffer.wrap(raw).order(order).int.toLong() and 0xFFFFFFFFL
        var valueOffset: Long = 0
    }

    private class Ifd(val offset: Long, val entries: List<Entry>, val next: ByteArray)

    private fun readHeader(f: RandomAccessFile): Header {
        val b = ByteArray(8)
        f.seek(0); f.readFully(b)
        val order = when {
            b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw NotATiffException("geen TIFF")
        }
        val bb = ByteBuffer.wrap(b).order(order)
        if (bb.getShort(2).toInt() != 42) throw NotATiffException("geen klassiek TIFF (BigTIFF wordt niet ondersteund)")
        return Header(order, bb.getInt(4).toLong() and 0xFFFFFFFFL)
    }

    private fun readIfd(f: RandomAccessFile, h: Header): Ifd {
        f.seek(h.ifdOffset)
        val nb = ByteArray(2); f.readFully(nb)
        val n = ByteBuffer.wrap(nb).order(h.order).short.toInt() and 0xFFFF
        val raw = ByteArray(n * 12); f.readFully(raw)
        val next = ByteArray(4); f.readFully(next)
        val entries = (0 until n).map { i ->
            val bb = ByteBuffer.wrap(raw, i * 12, 12).order(h.order)
            val tag = bb.short.toInt() and 0xFFFF
            val type = bb.short.toInt() and 0xFFFF
            val count = bb.int.toLong() and 0xFFFFFFFFL
            val value = ByteArray(4); bb.get(value)
            Entry(tag, type, count, value).also { it.valueOffset = it.valueOffsetOf(h.order) }
        }
        return Ifd(h.ifdOffset, entries, next)
    }

    private fun entryBytes(e: Entry, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(12).order(order).putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count.toInt()).put(e.raw).array()
}
