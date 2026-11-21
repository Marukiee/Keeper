package nl.markmaaktmedia.keeper.scan

/**
 * Hoe een bestand heet in de uitvoermap. Per soort bron één vaste naam, zodat twee versies van dezelfde foto nooit om dezelfde naam
 * vechten (MediaStore zou er stilletjes "naam (1).jpg" van maken) en "klaar" precies de omgekeerde vraag is: staat deze naam er al?
 *
 *  - Camera-JPG en Galerij-bewerking: dezelfde naam.
 *  - Camera-versie van een DNG: de naam met `.jpg`.
 *  - Export uit Lightroom: de naam met `-lr` erachter, want die heet hetzelfde als de JPG of DNG waar hij van gemaakt is.
 *  - Wat Keeper omzet (camera-JPG, Galerij-bewerking en de camera-versie van een DNG) krijgt de soort aan het eind van de naam: `-SDR` voor SDR en
 *    `-HDR` voor HDR behouden, zodat je in de map in één blik ziet wat wat is. Wat byte voor byte gekopieerd wordt, houdt zijn naam.
 *
 * Een bestand uit de tijd vóór `-SDR` en `-HDR` heet nog zoals het was en blijft "klaar": zie [candidates].
 */
object OutputName {
    const val LIGHTROOM_SUFFIX = "-lr"

    /** Wat er achter de naam komt: de soort van het bestand dat Keeper schrijft. */
    enum class Tag(val suffix: String) { None(""), Sdr("-SDR"), Hdr("-HDR") }

    /**
     * Een nieuwe versie naast wat er al staat: dezelfde naam als die vrij is, anders `naam-2.jpg`, `naam-3.jpg`, enzovoort. [taken] zijn
     * namen in kleine letters. Een nieuwe versie heet zo nooit hetzelfde als de eerste, dus "klaar" (zie hierboven) blijft op de eerste kijken.
     * `-SDR` en `-HDR` blijven het eind van de naam: `naam-SDR.jpg` wordt `naam-2-SDR.jpg`.
     */
    fun versioned(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val tagged = Tag.entries.firstOrNull { it != Tag.None && stem.endsWith(it.suffix, ignoreCase = true) }
        val base = if (tagged != null) stem.dropLast(tagged.suffix.length) else stem
        val tag = if (tagged != null) stem.takeLast(tagged.suffix.length) else ""
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base-$n$tag" else "$base-$n$tag.$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    /**
     * De naam van [item] in de uitvoermap, met de soort [kind] erachter. Een Lightroom-export krijgt die nooit, want hij gaat altijd zoals hij is mee.
     */
    fun of(item: MediaItem, lightroomFolder: String, kind: Tag = Tag.None): String {
        val stem = item.name.substringBeforeLast('.')
        val ext = item.name.substringAfterLast('.', "")
        val tag = kind.suffix
        return when {
            item.isDng -> "$stem$tag.jpg"
            item.relativePath.startsWith(lightroomFolder, ignoreCase = true) ->
                if (ext.isEmpty()) stem + LIGHTROOM_SUFFIX else "$stem$LIGHTROOM_SUFFIX.$ext"
            else -> if (ext.isEmpty()) stem + tag else "$stem$tag.$ext"
        }
    }

    /**
     * Alle namen die de uitvoer van [item] kan hebben: de gewone (een kopie, en alles van vóór `-SDR` en `-HDR`), die met `-SDR` en die met `-HDR`. Of er
     * al iets staat ("klaar"), kijkt naar alle drie.
     */
    fun candidates(item: MediaItem, lightroomFolder: String): List<String> =
        Tag.entries.map { of(item, lightroomFolder, it) }.distinct()
}
