package nl.markmaaktmedia.keeper.convert

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
}
