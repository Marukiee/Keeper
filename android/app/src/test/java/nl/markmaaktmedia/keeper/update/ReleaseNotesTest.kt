package nl.markmaaktmedia.keeper.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    /** De notities die de workflow als releasetekst gebruikt (`.github/release-notes/<tag>.md`): de app laat het Engelse deel zien en slaat het Nederlandse over. */
    @Test fun theHandWrittenNotesShowTheEnglishPartInTheApp() {
        val notes = File("../../.github/release-notes/v0.1.0.md")
        assertTrue("notities ontbreken: ${notes.absolutePath}", notes.exists())
        val shown = releaseSummary(notes.readText())
        assertTrue(shown, shown.contains("First release of Keeper."))
        assertTrue(shown, shown.contains("• Gallery of your new photos"))
        assertFalse(shown, shown.contains("Eerste versie"))
        assertFalse(shown, shown.contains("Nederlands"))
    }

    @Test fun commitSubjectsWithoutSectionsAreShownAsBullets() {
        val shown = releaseSummary("- Eerste\n- Tweede")
        assertTrue(shown, shown == "• Eerste\n• Tweede")
    }
}
