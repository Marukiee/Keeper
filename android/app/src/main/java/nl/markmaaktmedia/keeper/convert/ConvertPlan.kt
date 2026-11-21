package nl.markmaaktmedia.keeper.convert

import nl.markmaaktmedia.keeper.scan.OutputName
import nl.markmaaktmedia.keeper.scan.Photo

/** Wat er met één foto gebeurt. */
enum class How {
    /** HDR-JPEG naar SDR: gain map toepassen, tone map, opnieuw coderen. */
    Convert,
    /** De JPEG in IFD0 van een DNG (de camera-versie) naar SDR. */
    CameraVersion,
    /** Byte voor byte kopiëren: nooit opnieuw gecomprimeerd. */
    Copy,
}

class Step(val photo: Photo, val outName: String, val how: How)

/**
 * @param steps wat gedaan wordt, oudste eerst
 * @param existing foto's waarvan de uitvoernaam er al staat: overgeslagen
 * @param clashes foto's die dezelfde uitvoernaam zouden krijgen als een eerdere in deze run: overgeslagen, met een melding
 */
class Plan(val steps: List<Step>, val existing: List<Photo>, val clashes: List<Photo>)

/** Zuivere planning van een run: welke foto's, onder welke naam en hoe. Geen Android, dus op de JVM te testen. */
object ConvertPlan {
    /**
     * @param newVersion een nieuwe versie maken naast wat er al staat (zie [OutputName.versioned]) in plaats van de foto over te slaan.
     * @param kindOf de soort uitvoer van deze foto: SDR of HDR behouden. Alleen voor wat omgezet wordt ([How.Convert], [How.CameraVersion]): die krijgt
     *   `-SDR` of `-HDR` aan het eind van de naam; een kopie houdt zijn naam. Dat hangt van de instelling en de HDR-sterkte van de foto af (sterkte 0
     *   valt terug op SDR), dus dat bepaalt de aanroeper.
     */
    fun of(
        photos: List<Photo>, lightroomFolder: String, existingNames: Set<String>, newVersion: Boolean = false,
        kindOf: (Photo) -> OutputName.Tag = { OutputName.Tag.None },
    ): Plan {
        val have = existingNames.map { it.lowercase() }.toSet()
        val used = HashSet<String>()
        val steps = ArrayList<Step>()
        val existing = ArrayList<Photo>()
        val clashes = ArrayList<Photo>()
        for (photo in photos.filter { it.goesAlong }.sortedBy { it.takenMs }) {
            val how = when {
                photo.item.isDng -> How.CameraVersion
                photo.needsConversion -> How.Convert
                else -> How.Copy
            }
            val kind = if (how == How.Copy) OutputName.Tag.None else kindOf(photo)
            val name = OutputName.of(photo.item, lightroomFolder, kind).let { if (newVersion) OutputName.versioned(it, have + used) else it }
            val key = name.lowercase()
            // Staat er al een uitvoer van deze foto onder een andere naam (zonder soort, van vóór `-SDR` en `-HDR`, of de andere soort)? Dan telt die als er al:
            // anders kwam elke oude foto er een tweede keer bij. Een nieuwe versie wil juist naast wat er staat.
            val otherName = !newVersion && OutputName.candidates(photo.item, lightroomFolder).any { it.lowercase() in have }
            when {
                key in have || otherName -> existing += photo
                !used.add(key) -> clashes += photo
                else -> steps += Step(photo, name, how)
            }
        }
        return Plan(steps, existing, clashes)
    }
}
