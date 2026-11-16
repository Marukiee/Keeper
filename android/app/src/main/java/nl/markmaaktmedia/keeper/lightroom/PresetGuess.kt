package nl.markmaaktmedia.keeper.lightroom

import kotlin.math.abs

/**
 * Raadt de preset van een RAW uit de camera-JPG's die kort ervoor of erna zijn genomen (een DNG zelf noemt hem niet).
 * Puur rekenwerk, zodat het getest kan worden met echte tijdstippen.
 */
object PresetGuess {
    const val WINDOW_MS = 3 * 60_000L

    /** Een camera-JPG in de buurt. [name] is de presetnaam uit de trailer, of null als de JPG geen preset had. */
    class Neighbour(val takenMs: Long, val name: String?)

    /**
     * De presetnaam die bij de RAW van [takenMs] hoort: een naam, "" als de buren zeggen dat er geen preset was, of null als dat niet te zeggen is
     * (geen JPG binnen [WINDOW_MS], of twee JPG's met een andere preset liggen ongeveer even ver weg: dan kan er tussendoor gewisseld zijn).
     */
    fun decide(takenMs: Long, neighbours: List<Neighbour>): String? {
        val near = neighbours.filter { abs(it.takenMs - takenMs) <= WINDOW_MS }.sortedBy { abs(it.takenMs - takenMs) }
        val first = near.firstOrNull() ?: return null
        val answer = first.name.orEmpty()
        val other = near.firstOrNull { it.name.orEmpty() != answer } ?: return answer
        val d1 = abs(first.takenMs - takenMs)
        val d2 = abs(other.takenMs - takenMs)
        // Alleen als de dichtstbijzijnde minstens twee keer zo dichtbij is, vertrouwen we hem.
        return if (d2 >= 2 * d1) answer else null
    }
}
