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
     * @param sdrOutput wordt de uitvoer van deze foto SDR? Alleen voor wat omgezet wordt ([How.Convert], [How.CameraVersion]): die krijgt `-SDR` aan het
     *   eind van de naam. Met HDR behouden is het een HDR-bestand en blijft de naam zoals hij was; een kopie ook. Dat hangt van de instelling en de
     *   HDR-sterkte van de foto af, dus dat bepaalt de aanroeper.
     */
    fun of(
        photos: List<Photo>, lightroomFolder: String, existingNames: Set<String>, newVersion: Boolean = false,
        sdrOutput: (Photo) -> Boolean = { false },
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
            val sdr = how != How.Copy && sdrOutput(photo)
            val name = OutputName.of(photo.item, lightroomFolder, sdr).let { if (newVersion) OutputName.versioned(it, have + used) else it }
            val key = name.lowercase()
            // Een uitvoer van vóór `-SDR` heet nog zoals hij was en telt als er al: anders kwam elke oude foto er nu een tweede keer bij.
            val oldName = !newVersion && sdr && OutputName.of(photo.item, lightroomFolder).lowercase() in have
            when {
                key in have || oldName -> existing += photo
                !used.add(key) -> clashes += photo
                else -> steps += Step(photo, name, how)
            }
        }
        return Plan(steps, existing, clashes)
    }
}
