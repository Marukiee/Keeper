package nl.markmaaktmedia.keeper.lightroom

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import nl.markmaaktmedia.keeper.lightroom.PresetGuess.Neighbour

/** Tijdstippen uit de echte testreeks van 2026-11-16 (seconden na 13:00). */
class PresetGuessTest {
    private fun t(min: Int, sec: Int) = (min * 60L + sec) * 1000
    private val jpgs = listOf(
        Neighbour(t(26, 19), "Warm flash"), Neighbour(t(26, 31), "Warm flash"), Neighbour(t(26, 35), "Warm flash"), Neighbour(t(26, 37), "Warm flash"),
        Neighbour(t(27, 36), "Vintage"), Neighbour(t(29, 6), "Fresh"), Neighbour(t(29, 9), "Fresh"),
    )

    @Test fun `RAW vlak na een JPG krijgt die preset`() {
        assertEquals("Vintage", PresetGuess.decide(t(27, 39), jpgs))
        assertEquals("Warm flash", PresetGuess.decide(t(26, 42), jpgs))
    }

    @Test fun `RAW tussen twee presets op ongeveer gelijke afstand is onbekend`() {
        assertNull(PresetGuess.decide(t(28, 34), jpgs)) // 58 s na Vintage, 32 s voor Fresh: minder dan twee keer zo dichtbij
        assertNull(PresetGuess.decide(t(28, 12), jpgs)) // 36 s na Vintage, 54 s voor Fresh
    }

    @Test fun `RAW die duidelijk dichter bij de ene preset ligt krijgt die`() {
        assertEquals("Fresh", PresetGuess.decide(t(28, 41), jpgs)) // 25 s voor Fresh, 65 s na Vintage
        assertEquals("Vintage", PresetGuess.decide(t(28, 1), jpgs)) // 25 s na Vintage, 65 s voor Fresh
    }

    @Test fun `RAW ver van elke JPG is onbekend`() {
        assertNull(PresetGuess.decide(t(40, 0), jpgs))
        assertNull(PresetGuess.decide(t(10, 0), emptyList()))
    }

    @Test fun `een JPG zonder preset geeft een lege naam`() {
        assertEquals("", PresetGuess.decide(t(5, 0), listOf(Neighbour(t(5, 10), null))))
    }

    @Test fun `de dichtstbijzijnde wint pas als hij twee keer zo dichtbij is`() {
        val n = listOf(Neighbour(t(0, 0), "Fresh"), Neighbour(t(2, 0), "Vintage"))
        assertEquals("Fresh", PresetGuess.decide(t(0, 40), n)) // 40 s tegen 80 s
        assertNull(PresetGuess.decide(t(0, 50), n)) // 50 s tegen 70 s
    }
}
