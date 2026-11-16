package nl.markmaaktmedia.keeper.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

/**
 * Waar elke zichtbare tegel van de galerij op het scherm staat, per foto-id. De galerij meldt alleen de laag van elke tegel (één keer,
 * niet bij elke verschuiving onder het scrollen); de viewer rekent pas bij het sluiten uit waar die nu staat om de foto naar zijn
 * eigen tegel te laten vliegen. Geen Compose-state: een tegel die onder het scrollen verschuift kost geen hersamenstelling.
 */
class TileBounds {
    val tiles = HashMap<Long, LayoutCoordinates>()

    /** De plek van de tegel op het scherm, of null als hij er niet (meer) is. */
    fun rect(id: Long): Rect? = tiles[id]?.takeIf { it.isAttached }?.boundsInRoot()
}
