package nl.markmaaktmedia.keeper.update

import java.io.File
import nl.markmaaktmedia.markupdate.releaseHighlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    /** De notities die de workflow als releasetekst gebruikt (`.github/release-notes/<tag>.md`): de kaart toont er de eerste twee punten van, in het Engels of het Nederlands. */
    @Test fun theHandWrittenNotesGiveTwoLinesForTheCard() {
        val notes = File("../../.github/release-notes/v0.1.0.md")
        assertTrue("notities ontbreken: ${notes.absolutePath}", notes.exists())
        val english = releaseHighlights(notes.readText(), dutch = false)
        assertEquals(english.toString(), 2, english.size)
        assertTrue(english.toString(), english[0].startsWith("Gallery of your new photos"))
        val dutch = releaseHighlights(notes.readText(), dutch = true)
        assertEquals(dutch.toString(), 2, dutch.size)
        assertTrue(dutch.toString(), dutch[0].startsWith("Galerij van je nieuwe foto"))
    }
}
