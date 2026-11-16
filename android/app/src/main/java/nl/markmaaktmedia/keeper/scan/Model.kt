package nl.markmaaktmedia.keeper.scan

/** Eén bestand uit MediaStore, zonder Android-types zodat de logica op de JVM te testen is. */
data class MediaItem(
    val id: Long,
    val name: String,
    /** Zoals MediaStore het geeft: "DCIM/Camera/", met schuine streep erachter. */
    val relativePath: String,
    val size: Long,
    val dateAddedMs: Long,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    val mime: String,
    /** Hartje uit MediaStore. Alleen wat de Galerij daar zelf zet, zie IS_FAVORITE. */
    val isFavorite: Boolean = false,
) {
    val isDng get() = name.endsWith(".dng", ignoreCase = true) || mime == "image/x-adobe-dng"
    val isJpeg get() = mime == "image/jpeg"
    val baseName get() = name.substringBeforeLast('.').lowercase()
    val megapixels get() = width.toLong() * height / 1_000_000.0
}

/** Wat de kop van een JPEG zegt over HDR. Per bestand vastgesteld, nooit aangenomen. */
data class Probe(val hasGainMap: Boolean, val hasMpf: Boolean = false, val width: Int = 0, val height: Int = 0)

enum class Status {
    /** JPG met gain map: wordt omgezet naar SDR. */
    Convert,
    /** JPG zonder HDR: gaat mee zoals hij is. */
    AsIs,
    /** Export uit Lightroom: gaat mee zoals hij is, nooit opnieuw gecomprimeerd. */
    LightroomExport,
    /** Kopie bewerkt in de Galerij (IMG_YYYYMMDD_HHMMSS.jpg): gaat mee zoals hij is. */
    GalleryEdit,
    /** RAW zonder bewerking: wacht op Mark. */
    NeedsEdit,
    /** RAW waarvan de camera-versie (IFD0 van de DNG, filter erop, eigen gain map) wordt gebruikt. */
    CameraVersion,
    /** RAW met een Lightroom-export ernaast: de RAW zelf blijft liggen. */
    RawEdited,
    /** Origineel waarvan een Galerij-bewerking bestaat: het origineel blijft liggen. */
    OriginalReplaced,
    /** Camera-JPG waarvan een Lightroom-export bestaat: het origineel blijft liggen en Opruimen laat het staan. */
    OriginalExported,
    /** Camera-JPG die Mark niet bewaard heeft: gaat niet mee en kan met Opruimen naar de prullenbak. */
    NotKept,
    /** Lightroom-export of Galerij-bewerking die Mark zelf uitzet: gaat niet mee, blijft altijd staan. */
    Skipped,
    Done,
}

data class Photo(val item: MediaItem, val status: Status, val hdr: Boolean) {
    /** Gaat mee naar de uitvoermap. */
    val goesAlong get() = status in ALONG
    /** Moet door de HDR-naar-SDR-conversie. */
    val needsConversion get() = status == Status.Convert || status == Status.CameraVersion || (status == Status.GalleryEdit && hdr)
    /** Kan Mark deze aan- of uitzetten? Wat blijft liggen of al klaar is niet. */
    val selectable get() = status in SELECTABLE
    /** Aan betekent: gaat mee. Dat is het vinkje in de galerij. */
    val selected get() = goesAlong
    /**
     * Mag Opruimen deze naar de prullenbak sturen? Alleen camera-JPG's die niet bewaard zijn en geen favoriet.
     * RAW's, Lightroom-exports, Galerij-bewerkingen en originelen daarvan blijven altijd staan.
     */
    val cleanable get() = status == Status.NotKept && !item.isFavorite
    /** Gebruikt de uitzetlijst (Lightroom-export en Galerij-bewerking staan standaard aan); de rest staat standaard uit. */
    val defaultsOn get() = status == Status.LightroomExport || status == Status.GalleryEdit || status == Status.Skipped
    /** Wanneer genomen; zonder opnametijd de tijd dat het bestand erbij kwam. */
    val takenMs get() = item.dateTakenMs.takeIf { it > 0 } ?: item.dateAddedMs

    companion object {
        private val ALONG = setOf(Status.Convert, Status.AsIs, Status.LightroomExport, Status.GalleryEdit, Status.CameraVersion)
        private val SELECTABLE = ALONG + Status.NeedsEdit + Status.Skipped + Status.NotKept
    }
}

/** Het getal bij "Vandaag". Groottes zijn schattingen: de echte komen na de conversie. */
data class Summary(
    val count: Map<Status, Int>,
    val goingCount: Int,
    val currentBytes: Long,
    val expectedBytes: Long,
) {
    val savedBytes get() = (currentBytes - expectedBytes).coerceAtLeast(0)
}

/**
 * De drie sterktes uit fase 0 (belichting, knie). Hier alleen als keuze met uitleg; de berekening zelf komt in fase 2.
 * Gemeten op de tuinfoto: pixels op wit 1,7% bij normaal, 1,1% bij lichten-behouden.
 */
enum class ToneMap(val id: String, val exposure: Double, val knee: Double) {
    Licht("licht", 1.00, 0.60),
    Normaal("normaal", 0.90, 0.45),
    LichtenBehouden("lichten-behouden", 0.80, 0.40);

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Normaal }
}

/** Bits per pixel van jpegli per kwaliteit, gemeten op de 50 MP-tuinfoto van fase 0. */
object JpegQuality {
    val options = listOf(90, 93, 94, 95)
    fun bitsPerPixel(q: Int): Double = when {
        q <= 85 -> 0.57
        q <= 90 -> 0.72
        q <= 93 -> 0.92
        q == 94 -> 1.0
        else -> 1.15
    }
}
