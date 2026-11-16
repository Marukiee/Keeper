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
    fun of(photos: List<Photo>, lightroomFolder: String, existingNames: Set<String>): Plan {
        val have = existingNames.map { it.lowercase() }.toSet()
        val used = HashSet<String>()
        val steps = ArrayList<Step>()
        val existing = ArrayList<Photo>()
        val clashes = ArrayList<Photo>()
        for (photo in photos.filter { it.goesAlong }.sortedBy { it.takenMs }) {
            val name = OutputName.of(photo.item, lightroomFolder)
            val key = name.lowercase()
            when {
                key in have -> existing += photo
                !used.add(key) -> clashes += photo
                else -> steps += Step(
                    photo, name,
                    when {
                        photo.item.isDng -> How.CameraVersion
                        photo.needsConversion -> How.Convert
                        else -> How.Copy
                    },
                )
            }
        }
        return Plan(steps, existing, clashes)
    }
}
