package nl.markmaaktmedia.keeper.convert

import nl.markmaaktmedia.keeper.scan.MediaItem
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalsHidingTest {
    private fun photo(
        name: String = "IMG1.jpg", path: String = "DCIM/Camera/", mime: String = "image/jpeg", status: Status = Status.Convert, favorite: Boolean = false,
    ) = Photo(MediaItem(1, name, path, 5_000_000, 1_000, 1_000, 4000, 3000, mime, favorite), status, hdr = status == Status.Convert)

    private fun eligible(p: Photo, how: How = How.Convert, whole: Boolean = true) = OriginalsHiding.eligible(p, how, whole)

    @Test fun `een camera-JPG uit DCIM die in een hele run is omgezet of gekopieerd mag verborgen worden`() {
        assertTrue(eligible(photo()))
        assertTrue(eligible(photo(status = Status.AsIs), How.Copy))
    }

    @Test fun `een RAW nooit, ook niet als de camera-versie ervan is omgezet`() {
        assertFalse(eligible(photo("IMG1.dng", mime = "image/x-adobe-dng", status = Status.CameraVersion), How.CameraVersion))
        // Ook niet als een DNG ten onrechte als omgezet binnenkomt.
        assertFalse(eligible(photo("IMG1.dng", mime = "image/x-adobe-dng"), How.Convert))
    }

    @Test fun `Lightroom-exports en Galerij-bewerkingen nooit`() {
        assertFalse(eligible(photo(path = "Pictures/AdobeLightroom/", status = Status.LightroomExport), How.Copy))
        assertFalse(eligible(photo("IMG_20261114_120713.jpg", status = Status.GalleryEdit), How.Copy))
        assertFalse(eligible(photo("IMG_20261114_120713.jpg", status = Status.GalleryEdit), How.Convert))
    }

    @Test fun `alleen uit DCIM en alleen JPEG`() {
        assertFalse(eligible(photo(path = "Pictures/Keeper/")))
        assertFalse(eligible(photo(path = "Download/")))
        assertFalse(eligible(photo("a.png", mime = "image/png", status = Status.AsIs), How.Copy))
        assertTrue(eligible(photo(path = "DCIM/Camera/2026/")))
    }

    @Test fun `een favoriet blijft staan`() {
        assertFalse(eligible(photo(favorite = true)))
    }

    @Test fun `bij alleen deze foto en een nieuwe versie blijft het origineel staan`() {
        assertFalse(eligible(photo(), whole = false))
        assertFalse(eligible(photo(status = Status.AsIs), How.Copy, whole = false))
    }

    @Test fun `de regel voor opslaan en teruglezen is heen en terug`() {
        val e = HideEntry(58, "IMG20261113111455.jpg", 50_337_265, 60, 24_528_357, true)
        assertEquals(e, HideEntry.decode(e.encode()))
        val odd = HideEntry(1, "naam met | streep.jpg", 2, 3, 4, false)
        assertEquals(odd, HideEntry.decode(odd.encode()))
    }

    @Test fun `kapotte regels geven null`() {
        assertNull(HideEntry.decode(""))
        assertNull(HideEntry.decode("1|2|3"))
        assertNull(HideEntry.decode("x|2|3|4|1|a.jpg"))
    }

    @Test fun `dezelfde vorm, ook gedraaid`() {
        assertTrue(OriginalsHiding.sameShape(4000, 3000, 4000, 3000))
        assertTrue(OriginalsHiding.sameShape(4000, 3000, 3000, 4000))
        assertFalse(OriginalsHiding.sameShape(4000, 3000, 4000, 2999))
        assertFalse(OriginalsHiding.sameShape(0, 0, 0, 0))
        assertFalse(OriginalsHiding.sameShape(4000, 3000, 0, 0))
    }
}
