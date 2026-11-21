package nl.markmaaktmedia.keeper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepEntriesTest {
    @Test fun `de keuze per foto komt er zo uit als hij erin ging`() {
        val map = mapOf("IMG1.jpg" to true, "IMG 2 (kopie).jpg" to false)
        assertEquals(map, KeepEntries.decode(KeepEntries.encode(map)))
    }

    @Test fun `wat geen keuze is wordt overgeslagen`() {
        val set = setOf("goed.jpg|1", "uit.jpg|0", "kapot|", "|1", "geen-streep", "raar.jpg|2", "tekst.jpg|ja")
        assertEquals(mapOf("goed.jpg" to true, "uit.jpg" to false), KeepEntries.decode(set))
        assertEquals(emptyMap<String, Boolean>(), KeepEntries.decode(null))
    }
}
