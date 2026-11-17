package nl.markmaaktmedia.keeper.scan

/**
 * Hoe een bestand heet in de uitvoermap. Per soort bron één vaste naam, zodat twee versies van dezelfde foto nooit om dezelfde naam
 * vechten (MediaStore zou er stilletjes "naam (1).jpg" van maken) en "klaar" precies de omgekeerde vraag is: staat deze naam er al?
 *
 *  - Camera-JPG en Galerij-bewerking: dezelfde naam.
 *  - Camera-versie van een DNG: de naam met `.jpg`.
 *  - Export uit Lightroom: de naam met `-lr` erachter, want die heet hetzelfde als de JPG of DNG waar hij van gemaakt is.
 */
object OutputName {
    const val LIGHTROOM_SUFFIX = "-lr"

    /**
     * Een nieuwe versie naast wat er al staat: dezelfde naam als die vrij is, anders `naam-2.jpg`, `naam-3.jpg`, enzovoort. [taken] zijn
     * namen in kleine letters. Een nieuwe versie heet zo nooit hetzelfde als de eerste, dus "klaar" (zie hierboven) blijft op de eerste kijken.
     */
    fun versioned(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    fun of(item: MediaItem, lightroomFolder: String): String {
        val stem = item.name.substringBeforeLast('.')
        val ext = item.name.substringAfterLast('.', "")
        return when {
            item.isDng -> "$stem.jpg"
            item.relativePath.startsWith(lightroomFolder, ignoreCase = true) ->
                if (ext.isEmpty()) stem + LIGHTROOM_SUFFIX else "$stem$LIGHTROOM_SUFFIX.$ext"
            else -> item.name
        }
    }
}
