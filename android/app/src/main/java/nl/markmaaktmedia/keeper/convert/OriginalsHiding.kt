package nl.markmaaktmedia.keeper.convert

import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status

/**
 * Een origineel dat naar de prullenbak mag zodra de uitvoer klopt (zie [OriginalsHider]): wat er van het origineel en van de uitvoer bekend was toen
 * de uitvoer klaar was. De controle vlak voor het verbergen vergelijkt hiermee wat er dan in MediaStore staat.
 *
 * @param hdr de uitvoer is een Ultra HDR-bestand (HDR behouden): de gain map moet er dan nog in zitten.
 */
data class HideEntry(val originalId: Long, val name: String, val originalSize: Long, val outputId: Long, val outputSize: Long, val hdr: Boolean) {
    /** Een regel voor de instellingen (een stringset in DataStore). De naam staat achteraan, want alleen die kan een `|` bevatten. */
    fun encode(): String = "$originalId|$originalSize|$outputId|$outputSize|${if (hdr) 1 else 0}|$name"

    companion object {
        fun decode(line: String): HideEntry? {
            val p = line.split('|', limit = 6)
            if (p.size != 6) return null
            return HideEntry(
                p[0].toLongOrNull() ?: return null, p[5], p[1].toLongOrNull() ?: return null,
                p[2].toLongOrNull() ?: return null, p[3].toLongOrNull() ?: return null, p[4] == "1",
            )
        }
    }
}

/** De regels voor welke originelen verborgen mogen worden. Zuiver, dus op de JVM te testen. */
object OriginalsHiding {
    /**
     * Mag het origineel van [photo], dat in een run met [how] is weggeschreven, na controle naar de prullenbak?
     *
     * Alleen camera-JPG's uit DCIM die Keeper in deze run zelf schreef (omgezet of byte voor byte gekopieerd) en die geen favoriet zijn. Nooit een RAW
     * (de camera-versie in de uitvoer vervangt de RAW niet), nooit een Lightroom-export of Galerij-bewerking (dat zijn Mark's eigen resultaten, ze
     * staan buiten DCIM en worden ook door Opruimen met rust gelaten), en alleen als het een hele run was: bij "alleen deze foto" en "nieuwe versie" is
     * Mark nog aan het uitproberen en heeft hij het origineel nog nodig.
     */
    fun eligible(photo: Photo, how: How, wholeRun: Boolean): Boolean =
        wholeRun &&
            (how == How.Convert || how == How.Copy) &&
            (photo.status == Status.Convert || photo.status == Status.AsIs) &&
            photo.item.isJpeg && !photo.item.isDng &&
            photo.item.relativePath.startsWith("DCIM/") &&
            !photo.item.isFavorite

    /** Of twee afmetingen dezelfde foto kunnen zijn, ook als de een gedraaid staat (MediaStore telt breedte en hoogte soms vóór de Exif-draaiing). */
    fun sameShape(w1: Int, h1: Int, w2: Int, h2: Int): Boolean =
        w1 > 0 && h1 > 0 && ((w1 == w2 && h1 == h2) || (w1 == h2 && h1 == w2))
}
