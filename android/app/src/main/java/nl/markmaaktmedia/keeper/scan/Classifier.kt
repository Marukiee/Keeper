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
        val exports = live.filter(inLightroom).groupBy({ it.baseName }, { it.id })
        // Een bewerkte kopie houdt de EXIF-opnametijd van het origineel; zo vinden we het origineel terug.
        val galleryCopies = live.filter { isGalleryEdit(it.name) && it.dateTakenMs > 0 }.groupBy({ it.dateTakenMs }, { it.id })
        val done = doneNames.map { it.lowercase() }.toSet()

        return live.mapNotNull { item ->
            val hdr = probes[item.id]?.hasGainMap == true
            val lightroom = inLightroom(item)
            val galleryCopy = isGalleryEdit(item.name)
            // Klaar is de omgekeerde vraag van de naam in de uitvoermap (zie OutputName), met of zonder `-SDR`. Een DNG telt alleen als hij bewaard is:
            // die naam kan ook van een JPG komen.
            val isDone = OutputName.candidates(item, lightroomFolder).any { it.lowercase() in done } && (!item.isDng || item.name in kept)
            val isOriginal = !isDone && !lightroom && !galleryCopy
            // Is er van dit origineel een bewerking? Een Lightroom-export met dezelfde naam, of een Galerij-kopie met dezelfde opnametijd.
            val editIds = when {
                !isOriginal -> null
                item.isDng -> exports[item.baseName]
                item.isJpeg -> galleryCopies[item.dateTakenMs]?.takeIf { item.dateTakenMs > 0 } ?: exports[item.baseName]
                else -> null
            }.orEmpty()
            val edit = when {
                editIds.isEmpty() -> Edit.None
                item.isJpeg && item.dateTakenMs > 0 && item.dateTakenMs in galleryCopies -> Edit.Replaced
                else -> Edit.Exported
            }
            val status = when {
                isDone -> Status.Done
                item.name in skipped && (lightroom || galleryCopy) -> Status.Skipped
                lightroom -> Status.LightroomExport
                galleryCopy -> Status.GalleryEdit
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
            Photo(item, status, hdr, item.name in rejected, edit, editIds, markedKept = isDone && item.name in kept)
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
