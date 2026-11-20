package nl.markmaaktmedia.keeper.scan

/**
 * Hoe een bestand heet in de uitvoermap. Per soort bron één vaste naam, zodat twee versies van dezelfde foto nooit om dezelfde naam
 * vechten (MediaStore zou er stilletjes "naam (1).jpg" van maken) en "klaar" precies de omgekeerde vraag is: staat deze naam er al?
 *
 *  - Camera-JPG en Galerij-bewerking: dezelfde naam.
 *  - Camera-versie van een DNG: de naam met `.jpg`.
 *  - Export uit Lightroom: de naam met `-lr` erachter, want die heet hetzelfde als de JPG of DNG waar hij van gemaakt is.
 *  - Wat Keeper naar SDR omzet (camera-JPG, Galerij-bewerking en de camera-versie van een DNG): `-SDR` aan het eind van de naam, zodat je in de
 *    map in één blik ziet wat SDR is. Wat HDR blijft (HDR behouden) en wat byte voor byte gekopieerd wordt, houdt zijn naam.
 *
 * Een bestand uit de tijd vóór `-SDR` heet nog zoals het was en blijft "klaar": zie [candidates].
 */
object OutputName {
    const val LIGHTROOM_SUFFIX = "-lr"
    const val SDR_SUFFIX = "-SDR"

    /**
     * Een nieuwe versie naast wat er al staat: dezelfde naam als die vrij is, anders `naam-2.jpg`, `naam-3.jpg`, enzovoort. [taken] zijn
     * namen in kleine letters. Een nieuwe versie heet zo nooit hetzelfde als de eerste, dus "klaar" (zie hierboven) blijft op de eerste kijken.
     * `-SDR` blijft het eind van de naam: `naam-SDR.jpg` wordt `naam-2-SDR.jpg`.
     */
    fun versioned(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val tagged = stem.endsWith(SDR_SUFFIX, ignoreCase = true)
        val base = if (tagged) stem.dropLast(SDR_SUFFIX.length) else stem
        val tag = if (tagged) stem.takeLast(SDR_SUFFIX.length) else ""
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base-$n$tag" else "$base-$n$tag.$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    /**
     * De naam van [item] in de uitvoermap. [sdr]: het wordt een SDR-bestand, dus met `-SDR` erachter. Een Lightroom-export krijgt dat nooit, want
     * die gaat altijd zoals hij is mee.
     */
    fun of(item: MediaItem, lightroomFolder: String, sdr: Boolean = false): String {
        val stem = item.name.substringBeforeLast('.')
        val ext = item.name.substringAfterLast('.', "")
        val tag = if (sdr) SDR_SUFFIX else ""
        return when {
            item.isDng -> "$stem$tag.jpg"
            item.relativePath.startsWith(lightroomFolder, ignoreCase = true) ->
                if (ext.isEmpty()) stem + LIGHTROOM_SUFFIX else "$stem$LIGHTROOM_SUFFIX.$ext"
            else -> if (ext.isEmpty()) stem + tag else "$stem$tag.$ext"
        }
    }

    /**
     * Alle namen die de uitvoer van [item] kan hebben: de gewone (een kopie, HDR behouden, en alles van vóór `-SDR`) en die met `-SDR`. Of er al
     * iets staat ("klaar"), kijkt naar alle twee.
     */
    fun candidates(item: MediaItem, lightroomFolder: String): List<String> =
        listOf(of(item, lightroomFolder), of(item, lightroomFolder, sdr = true)).distinct()
}
