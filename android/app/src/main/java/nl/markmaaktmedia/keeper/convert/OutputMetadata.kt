package nl.markmaaktmedia.keeper.convert

/** De kop van een Ultra HDR-uitvoer: de segmenten, en waar de MPF-directory erin zit (voor [UltraHdrWriter.mpf], als de lengte van het beeld bekend is). */
class UltraHdrHeader(val segments: List<ByteArray>, val mpfOffset: Long)

/**
 * Wat er in de SDR-JPEG komt. De Exif van de camera gaat ongewijzigd mee (datum, GPS, lens, belichting, MakerNote) en het ICC-profiel ook
 * (Display P3 zoals de camera het zet, want de pixels blijven in dat gamut). Weg blijft alles wat bij de HDR hoort: de XMP met `hdrgm` en de
 * container-lijst, de MPF-directory, de gain map zelf, de QTI-debugsegmenten en de Oppo-trailer achter het beeld.
 */
object OutputMetadata {
    fun segments(head: JpegHead): List<ByteArray> = buildList {
        head.segments.firstOrNull { it.marker == JpegHead.APP1 && it.startsWith("Exif\u0000\u0000") }
            ?.payload?.let { add(JpegSegments.app(JpegSegments.APP1, it)) }
        head.icc()?.let { addAll(JpegSegments.icc(it)) }
    }

    /**
     * HDR behouden: dezelfde Exif en hetzelfde ICC-profiel, plus wat van een Ultra HDR-bestand een Ultra HDR-bestand maakt: de XMP met de
     * container-lijst, het ISO-versieblok en de MPF-directory, in de volgorde van de Oppo (Exif, XMP, ISO, MPF, ICC). De MPF staat er met
     * lengte 0 in; de aanroeper zet de echte lengte erin met [UltraHdrWriter.mpf] zodra het hoofdbeeld klaar is. Het segment is altijd even lang.
     */
    fun ultraHdr(head: JpegHead, gainLength: Int): UltraHdrHeader {
        val list = ArrayList<ByteArray>()
        head.segments.firstOrNull { it.marker == JpegHead.APP1 && it.startsWith("Exif\u0000\u0000") }
            ?.payload?.let { list += JpegSegments.app(JpegSegments.APP1, it) }
        list += JpegSegments.app(JpegSegments.APP1, UltraHdrWriter.primaryXmp(gainLength))
        list += JpegSegments.app(JpegSegments.APP2, UltraHdrWriter.isoVersionBlock())
        val mpfOffset = 2L + list.sumOf { it.size }
        list += UltraHdrWriter.mpf(0, mpfOffset, gainLength.toLong())
        head.icc()?.let { list += JpegSegments.icc(it) }
        return UltraHdrHeader(list, mpfOffset)
    }
}
