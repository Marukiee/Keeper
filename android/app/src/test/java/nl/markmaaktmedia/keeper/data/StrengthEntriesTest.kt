package nl.markmaaktmedia.keeper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StrengthEntriesTest {
    @Test fun `de sterkte per foto gaat heen en terug`() {
        val map = mapOf("IMG20261116132619.jpg" to 40, "IMG 1.jpg" to 0, "a.jpg" to 100)
        assertEquals(map, StrengthEntries.decode(StrengthEntries.encode(map)))
    }

    @Test fun `kapotte of ontbrekende regels worden overgeslagen en procenten begrensd`() {
        val set = setOf("goed.jpg|55", "zonder-streep", "|10", "leeg.jpg|", "letters.jpg|abc", "te-veel.jpg|250", "min.jpg|-5")
        assertEquals(mapOf("goed.jpg" to 55, "te-veel.jpg" to 100, "min.jpg" to 0), StrengthEntries.decode(set))
        assertEquals(emptyMap<String, Int>(), StrengthEntries.decode(null))
    }
}
