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
