package nl.markmaaktmedia.keeper

import nl.markmaaktmedia.keeper.scan.Classifier
import nl.markmaaktmedia.keeper.scan.Edit
import nl.markmaaktmedia.keeper.scan.MediaItem
import nl.markmaaktmedia.keeper.scan.Probe
import nl.markmaaktmedia.keeper.scan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierTest {
    private var next = 1L
    private fun item(name: String, path: String = "DCIM/Camera/", taken: Long = 0, mime: String? = null, w: Int = 4000, h: Int = 3000, size: Long = 5_000_000) =
        MediaItem(next++, name, path, size, 1_000, taken, w, h, mime ?: if (name.endsWith(".dng")) "image/x-adobe-dng" else "image/jpeg")

    private fun run(
        items: List<MediaItem>,
        hdr: Set<Long> = emptySet(),
        skipped: Set<String> = emptySet(),
        camera: Set<String> = emptySet(),
        done: Set<String> = emptySet(),
        // Standaard is alles bewaard, zodat de tests de soort bekijken; "nietBewaard" test de keuze zelf.
        keepAll: Boolean = true,
    ) = Classifier.classify(items, items.associate { it.id to Probe(it.id in hdr) }, skipped, if (keepAll) camera + items.map { it.name } else camera, done, "Pictures/AdobeLightroom/")
        .associate { it.item.name to it.status }

    @Test fun hdrJpgIsConvertedAndPlainJpgGoesAsIs() {
        val a = item("IMG20261114120713.jpg"); val b = item("IMG20261114120816.jpg")
        val r = run(listOf(a, b), hdr = setOf(a.id))
        assertEquals(Status.Convert, r[a.name]); assertEquals(Status.AsIs, r[b.name])
    }

    @Test fun trashedAndImmichAreIgnored() {
        val r = run(listOf(item(".trashed-1234-IMG1.jpg"), item("IMG1.jpg", path = "DCIM/Immich/")))
        assertEquals(emptyMap<String, Status>(), r)
    }

    @Test fun rawWithoutExportNeedsEditingAndCanUseCameraVersion() {
        val raw = item("IMG20261114120548.dng")
        assertEquals(Status.NeedsEdit, run(listOf(raw), keepAll = false)[raw.name])
        assertEquals(Status.CameraVersion, run(listOf(raw), camera = setOf(raw.name), keepAll = false)[raw.name])
    }

    @Test fun rawWithLightroomExportIsSkippedAndExportGoesAsIs() {
        val raw = item("IMG20261114120548.dng"); val export = item("IMG20261114120548.jpg", path = "Pictures/AdobeLightroom/")
        val r = run(listOf(raw, export), keepAll = false)
        assertEquals(Status.RawEdited, r[raw.name]); assertEquals(Status.LightroomExport, r[export.name])
    }

    @Test fun keepingAnEditedOriginalWinsOverTheEdit() {
        val raw = item("IMG20261114120548.dng"); val export = item("IMG20261114120548.jpg", path = "Pictures/AdobeLightroom/")
        val photos = Classifier.classify(listOf(raw, export), emptyMap(), emptySet(), setOf(raw.name), emptySet(), "Pictures/AdobeLightroom/")
        val p = photos.first { it.item.id == raw.id }
        // Bewaard: de camera-versie gaat mee, en de tegel blijft laten zien dat er een bewerking bestaat.
        assertEquals(Status.CameraVersion, p.status); assertEquals(Edit.Exported, p.edit)
        assertEquals(true, p.selectable)
        // Al bewaard: geen vraag meer. En de export zelf is geen origineel met een bewerking.
        assertEquals(false, p.askBeforeKeeping)
        assertEquals(false, photos.first { it.item.id == export.id }.askBeforeKeeping)
        assertEquals(Edit.None, photos.first { it.item.id == export.id }.edit)
    }

    @Test fun editedOriginalsCanBePickedButDoNotGoAlong() {
        val raw = item("IMG1.dng"); val export = item("IMG1.jpg", path = "Pictures/AdobeLightroom/")
        val original = item("IMG20261114120713.jpg", taken = 555); val edit = item("IMG_20261114_220000.jpg", taken = 555)
        val photos = Classifier.classify(listOf(raw, export, original, edit), emptyMap(), emptySet(), emptySet(), emptySet(), "Pictures/AdobeLightroom/")
        for (name in listOf(raw.name, original.name)) {
            val p = photos.first { it.item.name == name }
            assertEquals(true, p.selectable); assertEquals(false, p.goesAlong); assertEquals(false, p.cleanable)
            // Bewaren zou hier een tweede versie naast de bewerkte geven: eerst vragen.
            assertEquals(true, p.askBeforeKeeping)
        }
        assertEquals(Edit.Replaced, photos.first { it.item.id == original.id }.edit)
    }

    @Test fun galleryEditReplacesTheOriginalByCaptureTime() {
        val original = item("IMG20261114120713.jpg", taken = 555); val edit = item("IMG_20261114_220000.jpg", taken = 555)
        val r = run(listOf(original, edit), keepAll = false)
        assertEquals(Status.OriginalReplaced, r[original.name]); assertEquals(Status.GalleryEdit, r[edit.name])
    }

    @Test fun skippedAndDoneWinOverTheKind() {
        val a = item("IMG1.jpg", path = "Pictures/AdobeLightroom/"); val b = item("IMG2.jpg")
        val r = run(listOf(a, b), skipped = setOf(a.name), done = setOf("img2.JPG"))
        assertEquals(Status.Skipped, r[a.name]); assertEquals(Status.Done, r[b.name])
    }

    @Test fun jpgIsNotKeptUntilMarkKeepsIt() {
        val a = item("IMG20261114120713.jpg"); val b = item("IMG20261114120816.jpg")
        val r = run(listOf(a, b), camera = setOf(b.name), keepAll = false)
        assertEquals(Status.NotKept, r[a.name]); assertEquals(Status.AsIs, r[b.name])
    }

    @Test fun onlyUnkeptCameraJpgsAreCleanable() {
        val bad = item("IMG20261114120713.jpg"); val good = item("IMG20261114120816.jpg"); val liked = item("IMG20261114120900.jpg").copy(isFavorite = true)
        val raw = item("IMG20261114121000.dng"); val export = item("IMG20261114121100.jpg", path = "Pictures/AdobeLightroom/")
        val photos = Classifier.classify(listOf(bad, good, liked, raw, export), emptyMap(), emptySet(), setOf(good.name), emptySet(), "Pictures/AdobeLightroom/")
        assertEquals(listOf(bad.name), photos.filter { it.cleanable }.map { it.item.name })
    }

    @Test fun cameraJpgWithLightroomExportIsNeverCleaned() {
        val original = item("IMG20261114120548.jpg"); val export = item("IMG20261114120548.jpg", path = "Pictures/AdobeLightroom/")
        val photos = Classifier.classify(listOf(original, export), emptyMap(), emptySet(), emptySet(), emptySet(), "Pictures/AdobeLightroom/")
        assertEquals(Status.OriginalExported, photos.first { it.item.id == original.id }.status)
        assertEquals(emptyList<String>(), photos.filter { it.cleanable }.map { it.item.name })
    }

    @Test fun lightroomExportsStayAlongUnlessMarkTurnsThemOff() {
        val export = item("IMG1.jpg", path = "Pictures/AdobeLightroom/")
        assertEquals(Status.LightroomExport, run(listOf(export), keepAll = false)[export.name])
        assertEquals(Status.Skipped, run(listOf(export), skipped = setOf(export.name), keepAll = false)[export.name])
    }

    @Test fun otherFilesAreNotListed() {
        assertNull(run(listOf(item("clip.mp4", mime = "video/mp4")))["clip.mp4"])
    }

    @Test fun summaryNeverExpectsMoreThanTheOriginal() {
        val big = item("IMG1.jpg", w = 8192, h = 6144, size = 18_000_000); val small = item("IMG2.jpg", w = 4000, h = 3000, size = 400_000)
        val photos = Classifier.classify(listOf(big, small), mapOf(big.id to Probe(true), small.id to Probe(true)), emptySet(), setOf(big.name, small.name), emptySet(), "x/")
        val s = Classifier.summarize(photos)
        assertEquals(18_400_000L, s.currentBytes)
        // 50,3 MP x 1,0 bpp = 6,3 MB; de kleine foto blijft op 0,4 MB
        assertEquals(6_291_456L + 400_000L, s.expectedBytes)
    }
}
