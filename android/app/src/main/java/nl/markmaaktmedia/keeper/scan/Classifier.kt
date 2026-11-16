package nl.markmaaktmedia.keeper.scan

/**
 * Bepaalt per bestand wat ermee gebeurt. Zuivere functie: dezelfde invoer geeft dezelfde uitkomst,
 * en er wordt niets gelezen of geschreven.
 */
object Classifier {
    /** Galerij-bewerking van de Oppo: IMG_20261114_120713.jpg, de camera zelf schrijft IMG20261114120713.jpg. */
    private val galleryEdit = Regex("""^IMG_\d{8}_\d{6}.*\.jpe?g$""", RegexOption.IGNORE_CASE)

    fun isGalleryEdit(name: String) = galleryEdit.matches(name)

    /** Wordt nooit getoond: prullenbak en wat Immich zelf heeft gedownload. */
    fun isIgnored(item: MediaItem) =
        item.name.startsWith(".trashed", ignoreCase = true) ||
            item.name.startsWith(".pending", ignoreCase = true) ||
            item.relativePath.startsWith("DCIM/Immich", ignoreCase = true)

    fun classify(
        items: List<MediaItem>,
        probes: Map<Long, Probe>,
        skipped: Set<String>,
        kept: Set<String>,
        doneNames: Set<String>,
        lightroomFolder: String,
        rejected: Set<String> = emptySet(),
    ): List<Photo> {
        val live = items.filterNot(::isIgnored)
        val inLightroom = { i: MediaItem -> i.relativePath.startsWith(lightroomFolder, ignoreCase = true) }
        val exportedBases = live.filter(inLightroom).map { it.baseName }.toSet()
        // Een bewerkte kopie houdt de EXIF-opnametijd van het origineel; zo vinden we het origineel terug.
        val editedTimes = live.filter { isGalleryEdit(it.name) && it.dateTakenMs > 0 }.map { it.dateTakenMs }.toSet()
        val done = doneNames.map { it.lowercase() }.toSet()

        return live.mapNotNull { item ->
            val hdr = probes[item.id]?.hasGainMap == true
            val isOriginal = item.name.lowercase() !in done && !inLightroom(item) && !isGalleryEdit(item.name)
            // Is er van dit origineel een bewerking? Een Lightroom-export met dezelfde naam, of een Galerij-kopie met dezelfde opnametijd.
            val edit = when {
                !isOriginal -> Edit.None
                item.isDng && item.baseName in exportedBases -> Edit.Exported
                item.isJpeg && item.dateTakenMs > 0 && item.dateTakenMs in editedTimes -> Edit.Replaced
                item.isJpeg && item.baseName in exportedBases -> Edit.Exported
                else -> Edit.None
            }
            val status = when {
                item.name.lowercase() in done -> Status.Done
                item.name in skipped && (inLightroom(item) || isGalleryEdit(item.name)) -> Status.Skipped
                inLightroom(item) -> Status.LightroomExport
                isGalleryEdit(item.name) -> Status.GalleryEdit
                // Bewaren is een bewuste keuze en wint van "er bestaat een bewerking": dan gaat ook de camera-versie mee.
                item.isDng -> when {
                    item.name in kept -> Status.CameraVersion
                    edit != Edit.None -> Status.RawEdited
                    else -> Status.NeedsEdit
                }
                item.isJpeg && item.name in kept -> if (hdr) Status.Convert else Status.AsIs
                edit == Edit.Replaced -> Status.OriginalReplaced
                edit == Edit.Exported -> Status.OriginalExported
                // Zonder keuze gaat een camera-JPG niet mee.
                item.isJpeg -> Status.NotKept
                else -> return@mapNotNull null
            }
            Photo(item, status, hdr, item.name in rejected, edit)
        }.sortedByDescending { it.item.dateTakenMs.takeIf { t -> t > 0 } ?: it.item.dateAddedMs }
    }

    /** Standaard: jpegli q94 geeft ongeveer 1,0 bit per pixel (q93 0,92 en q95 1,15, gemeten in fase 0). */
    const val EXPECTED_BPP = 1.0
    /** Een DNG-preview is ongeveer een vijfde van de DNG (14,5 MB gaf 2,1 MB, 80 MB gaf 18,5 MB). */
    private const val PREVIEW_SHARE = 0.2

    fun summarize(photos: List<Photo>, bitsPerPixel: Double = EXPECTED_BPP): Summary {
        val going = photos.filter { it.goesAlong }
        var current = 0L
        var expected = 0L
        for (p in going) {
            val now = if (p.item.isDng) (p.item.size * PREVIEW_SHARE).toLong() else p.item.size
            current += now
            expected += if (p.needsConversion) {
                // Nooit groter dan het origineel: een gladde foto wordt veel kleiner dan deze schatting.
                minOf(now, (p.item.width.toLong() * p.item.height * bitsPerPixel / 8).toLong())
            } else now
        }
        return Summary(photos.groupingBy { it.status }.eachCount(), going.size, current, expected)
    }
}
