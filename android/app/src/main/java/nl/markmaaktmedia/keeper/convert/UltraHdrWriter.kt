package nl.markmaaktmedia.keeper.convert

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * De onderdelen van een Ultra HDR-JPEG (HDR behouden): de XMP en het ISO-blok in het hoofdbeeld, de MPF-directory die naar de gain map wijst,
 * en de gain map zelf met de HDR-sterkte erin verwerkt. Alles in dezelfde vorm als de Oppo schrijft (en Android 15, Chrome en Foto's lezen).
 *
 * Een Ultra HDR-bestand is: hoofdbeeld (SDR, met XMP + ISO-versieblok + MPF) direct gevolgd door de gain map (een eigen JPEG, met de
 * hdrgm-XMP en het ISO-blok met de waarden). Android 15 en nieuwer lezen de waarden uit het ISO-blok, oudere versies uit de XMP; beide
 * moeten dus kloppen.
 */
object UltraHdrWriter {
    /** Het begin van elk ISO 21496-1-blok (APP2), inclusief de afsluitende nul. */
    const val ISO_URN = "urn:iso:std:iso:ts:21496:-1\u0000"

    private const val DENOMINATOR = 1_000_000L

    /** De XMP van het hoofdbeeld: de container-lijst (hoofdbeeld + gain map van [gainLength] bytes) en de hdrgm-versie. */
    fun primaryXmp(gainLength: Int): ByteArray {
        val xml = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">\n" +
            "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "    <rdf:Description xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
            "      xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
            "      xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n" +
            "      hdrgm:Version=\"1.0\">\n" +
            "      <Container:Directory><rdf:Seq>\n" +
            "        <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>\n" +
            "        <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"$gainLength\"/></rdf:li>\n" +
            "      </rdf:Seq></Container:Directory>\n" +
            "    </rdf:Description>\n" +
            "  </rdf:RDF>\n" +
            "</x:xmpmeta>\n"
        return (JpegHead.XMP_ID + xml).toByteArray(Charsets.UTF_8)
    }

    /** Het ISO-blok van het hoofdbeeld: alleen de versie (minimum 0, schrijver 0), de waarden staan in de gain map. */
    fun isoVersionBlock(): ByteArray = ISO_URN.toByteArray(Charsets.ISO_8859_1) + ByteArray(4)

    /**
     * Het ISO-blok van de gain map (ISO 21496-1): versie, vlaggen, de headroom van basis en alternatief, en per kanaal min, max, gamma en de
     * twee offsets, elk als breuk. Eén kanaal als R, G en B gelijk zijn, anders drie. [flags] is het vlaggenbyte van het origineel (bit 6, de
     * basiskleurruimte, blijft); bit 7 (meerdere kanalen) zet dit zelf.
     */
    fun isoBlock(m: GainMapMeta, flags: Int = 0x40): ByteArray {
        val channels = if (m.uniform()) 1 else 3
        val b = ByteBuffer.allocate(ISO_URN.length + 4 + 1 + 16 + 40 * channels).order(ByteOrder.BIG_ENDIAN)
        b.put(ISO_URN.toByteArray(Charsets.ISO_8859_1))
        b.putShort(0).putShort(0)
        b.put(((flags and 0x7F) or (if (channels == 3) 0x80 else 0)).toByte())
        unsigned(b, m.capMin); unsigned(b, m.capMax)
        for (c in 0 until channels) {
            signed(b, m.min[c]); signed(b, m.max[c]); unsigned(b, m.gamma[c].takeIf { it > 0f } ?: 1f)
            signed(b, m.offSdr[c]); signed(b, m.offHdr[c])
        }
        return b.array()
    }

    /**
     * De MPF-directory (APP2, compleet segment) voor een bestand van twee beelden: het hoofdbeeld van [primaryLength] bytes (SOI tot en met EOI)
     * en direct erachter de gain map van [gainLength] bytes. [segmentOffset] is waar dit segment (de FFE2) in het hoofdbeeld begint. De offset
     * van de gain map telt vanaf de TIFF-kop in dit segment: 8 bytes na het begin (marker, lengte en "MPF\0").
     */
    fun mpf(primaryLength: Long, segmentOffset: Long, gainLength: Long): ByteArray {
        val b = ByteBuffer.allocate(MPF_PAYLOAD).order(ByteOrder.BIG_ENDIAN)
        b.put("MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        b.put('M'.code.toByte()).put('M'.code.toByte()).putShort(0x002A).putInt(8)
        b.putShort(3)
        b.putShort(0xB000.toShort()).putShort(7).putInt(4).put("0100".toByteArray(Charsets.ISO_8859_1))
        b.putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(2)
        b.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(50)
        b.putInt(0)
        b.putInt(0x00030000).putInt(primaryLength.toInt()).putInt(0).putShort(0).putShort(0)
        b.putInt(0).putInt(gainLength.toInt()).putInt((primaryLength - (segmentOffset + 8)).toInt()).putShort(0).putShort(0)
        return JpegSegments.app(JpegSegments.APP2, b.array())
    }

    /**
     * De gain map ([gain], een complete JPEG) met de sterkte [strength] erin: de XMP en het ISO-blok (als die er al in zaten) komen vervangen
     * door versies van [meta] ([GainMapMeta.scaled]); al het andere, ook de beeldgegevens, blijft byte voor byte staan.
     */
    fun rewriteGainMap(gain: ByteArray, meta: GainMapMeta, strength: Float): ByteArray {
        require(gain.size > 4 && gain[0] == 0xFF.toByte() && gain[1] == 0xD8.toByte()) { "gain map is geen JPEG" }
        val scaled = meta.scaled(strength)
        val xmp = JpegSegments.app(JpegSegments.APP1, (JpegHead.XMP_ID + GainMapXmp.build(scaled)).toByteArray(Charsets.UTF_8))
        val head = ArrayList<ByteArray>()
        var sawXmp = false
        var p = 2
        while (p + 4 <= gain.size && gain[p] == 0xFF.toByte()) {
            val marker = gain[p + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val len = ((gain[p + 2].toInt() and 0xFF) shl 8) or (gain[p + 3].toInt() and 0xFF)
            val end = p + 2 + len
            if (len < 2 || end > gain.size) break
            val seg = gain.copyOfRange(p, end)
            val payload = 4
            head += when {
                marker == JpegSegments.APP1 && startsWith(seg, payload, JpegHead.XMP_ID) -> if (sawXmp) ByteArray(0) else { sawXmp = true; xmp }
                marker == JpegSegments.APP2 && startsWith(seg, payload, ISO_URN) -> {
                    val old = if (seg.size > payload + ISO_URN.length + 4) seg[payload + ISO_URN.length + 4].toInt() and 0xFF else 0x40
                    JpegSegments.app(JpegSegments.APP2, isoBlock(scaled, old))
                }
                else -> seg
            }
            p = end
        }
        val out = ByteArrayOutputStream(gain.size + 1024)
        out.write(0xFF); out.write(0xD8)
        if (!sawXmp) out.write(xmp)
        head.forEach { out.write(it) }
        out.write(gain, p, gain.size - p)
        return out.toByteArray()
    }

    private fun GainMapMeta.uniform(): Boolean {
        fun same(v: FloatArray) = v[0] == v[1] && v[1] == v[2]
        return same(min) && same(max) && same(gamma) && same(offSdr) && same(offHdr)
    }

    private fun startsWith(b: ByteArray, at: Int, text: String): Boolean {
        if (b.size < at + text.length) return false
        for (i in text.indices) if (b[at + i] != text[i].code.toByte()) return false
        return true
    }

    private fun signed(b: ByteBuffer, v: Float) { b.putInt((v.toDouble() * DENOMINATOR).roundToLong().toInt()); b.putInt(DENOMINATOR.toInt()) }

    private fun unsigned(b: ByteBuffer, v: Float) { b.putInt((v.coerceAtLeast(0f).toDouble() * DENOMINATOR).roundToLong().toInt()); b.putInt(DENOMINATOR.toInt()) }

    private const val MPF_PAYLOAD = 86
}
