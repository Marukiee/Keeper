package nl.markmaaktmedia.keeper.ui

import androidx.compose.ui.geometry.Rect

/**
 * Waar elke zichtbare tegel van de galerij op het scherm staat (schermcoördinaten), per foto-id. De galerij schrijft, de viewer leest
 * bij het sluiten om de foto naar zijn eigen tegel te laten vliegen. Geen Compose-state: alleen op dat ene moment gelezen, dus
 * een tegel die onder het scrollen verschuift kost geen hersamenstelling.
 */
class TileBounds {
    val rects = HashMap<Long, Rect>()
}
