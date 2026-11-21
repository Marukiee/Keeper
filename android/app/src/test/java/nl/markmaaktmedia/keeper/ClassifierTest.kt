package nl.markmaaktmedia.keeper

import nl.markmaaktmedia.keeper.scan.Classifier
import nl.markmaaktmedia.keeper.scan.Edit
import nl.markmaaktmedia.keeper.scan.MediaItem
import nl.markmaaktmedia.keeper.scan.Probe
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.scan.Summary
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
        // De bewerking is terug te vinden, zodat "alleen het origineel" die kan overslaan.
        assertEquals(listOf(export.id), photos.first { it.item.id == raw.id }.editIds)
        assertEquals(listOf(edit.id), photos.first { it.item.id == original.id }.editIds)
        assertEquals(emptyList<Long>(), photos.first { it.item.id == export.id }.editIds)
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

    @Test fun aFinishedPhotoCanBePickedKeptAndDiscardedButNeverGoesAlongAgain() {
        val lr = "Pictures/AdobeLightroom/"
        val kept = item("IMG20261120100000.jpg"); val discarded = item("IMG20261120100100.jpg"); val untouched = item("IMG20261120100200.jpg")
        val done = setOf(kept.name, discarded.name, untouched.name)
        val photos = Classifier.classify(listOf(kept, discarded, untouched), emptyMap(), emptySet(), setOf(kept.name), done, lr, rejected = setOf(discarded.name))
        val byName = photos.associateBy { it.item.name }
        for (p in photos) {
            // Klaar is te kiezen (dus de selectiebalk blijft staan), maar gaat niet nog een keer mee en Opruimen laat het staan.
            assertEquals(Status.Done, p.status); assertEquals(true, p.selectable); assertEquals(false, p.goesAlong); assertEquals(false, p.cleanable)
        }
        assertEquals(true, byName.getValue(kept.name).kept); assertEquals(false, byName.getValue(kept.name).discarded)
        assertEquals(false, byName.getValue(discarded.name).kept); assertEquals(true, byName.getValue(discarded.name).discarded)
        assertEquals(false, byName.getValue(untouched.name).kept); assertEquals(false, byName.getValue(untouched.name).discarded)
        // Wat al klaar is, wordt niet nog een keer gedaan en vraagt niet om een bewerking.
        assertEquals(false, byName.getValue(kept.name).askBeforeKeeping)
        assertEquals(Summary(emptyMap(), 0, 0, 0).goingCount, Classifier.summarize(photos).goingCount)
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

    @Test fun summaryWithHdrKeptAddsTheGainMapAndStaysUnderTheOriginal() {
        val big = item("IMG1.jpg", w = 8192, h = 6144, size = 18_000_000); val small = item("IMG2.jpg", w = 4000, h = 3000, size = 400_000)
        val photos = Classifier.classify(listOf(big, small), mapOf(big.id to Probe(true), small.id to Probe(true)), emptySet(), setOf(big.name, small.name), emptySet(), "x/")
        // HDR behouden: het hoofdbeeld wordt 6,3 MB (1,0 bit per pixel) en de gain map (0,16 van 18 MB = 2,9 MB) blijft; de kleine foto kan niet groter worden.
        val hdr = Classifier.summarize(photos, 1.0) { true }
        assertEquals((6_291_456L + 2_880_000L + 400_000L).toDouble(), hdr.expectedBytes.toDouble(), 4.0)
        // Voor SDR blijft het bij alleen het hoofdbeeld, dus HDR behouden is groter, maar nooit groter dan het origineel.
        val sdr = Classifier.summarize(photos, 1.0) { false }
        assertEquals(6_291_456L + 400_000L, sdr.expectedBytes)
        assertEquals(true, hdr.expectedBytes > sdr.expectedBytes && hdr.expectedBytes <= hdr.currentBytes)
    }

    @Test fun automaticResolutionShrinksTheHdrEstimateOfBigPhotosOnly() {
        val big = item("IMG1.jpg", w = 8192, h = 6144, size = 18_000_000); val small = item("IMG2.jpg", w = 4000, h = 3000, size = 400_000)
        val photos = Classifier.classify(listOf(big, small), mapOf(big.id to Probe(true), small.id to Probe(true)), emptySet(), setOf(big.name, small.name), emptySet(), "x/")
        val full = Classifier.summarize(photos, 1.0) { true }
        val auto = Classifier.summarize(photos, 1.0, autoResolution = true) { true }
        // De grote foto telt met de factor, de kleine (onder 30 MP) niet.
        assertEquals((6_291_456L + 2_880_000L) * Classifier.AUTO_HALVING + 400_000L, auto.expectedBytes.toDouble(), 4.0)
        assertEquals(true, auto.expectedBytes < full.expectedBytes)
        // SDR wordt nooit verkleind, ook niet met Automatisch.
        assertEquals(Classifier.summarize(photos, 1.0) { false }.expectedBytes, Classifier.summarize(photos, 1.0, autoResolution = true) { false }.expectedBytes)
    }

    // De naam in de uitvoermap bepaalt "klaar" (zie OutputName): per soort bron één vaste naam.
    @Test fun keptRawIsDoneWhenItsCameraVersionIsInTheOutputFolder() {
        val raw = item("IMG20261114133431.dng")
        assertEquals(Status.Done, run(listOf(raw), done = setOf("IMG20261114133431.jpg"))[raw.name])
        // Niet bewaard: dezelfde naam kan van een JPG komen, dus de RAW blijft wachten.
        assertEquals(Status.NeedsEdit, run(listOf(raw), done = setOf("IMG20261114133431.jpg"), keepAll = false)[raw.name])
    }

    @Test fun anOutputWithSdrInItsNameIsDoneToo() {
        val jpg = item("IMG20261114133431.jpg"); val raw = item("IMG20261114133432.dng")
        val r = run(listOf(jpg, raw), done = setOf("IMG20261114133431-SDR.jpg", "IMG20261114133432-SDR.jpg"))
        assertEquals(Status.Done, r[jpg.name]); assertEquals(Status.Done, r[raw.name])
        // Een export is er niet klaar mee: die heet altijd -lr.
        val export = item("IMG20261114133431.jpg", path = "Pictures/AdobeLightroom/")
        assertEquals(Status.LightroomExport, run(listOf(export), done = setOf("IMG20261114133431-SDR.jpg"))[export.name])
    }

    @Test fun lightroomExportIsDoneOnlyUnderItsOwnName() {
        val raw = item("IMG20261114133431.dng"); val export = item("IMG20261114133431.jpg", path = "Pictures/AdobeLightroom/")
        // De camera-versie van de RAW heet IMG...jpg; dat maakt de export niet klaar.
        val camera = run(listOf(raw, export), done = setOf("IMG20261114133431.jpg"))
        assertEquals(Status.Done, camera[raw.name]); assertEquals(Status.LightroomExport, camera[export.name])
        // Onder zijn eigen naam wel, en dan blijft de RAW zijn eigen werk doen.
        val both = run(listOf(raw, export), done = setOf("IMG20261114133431-lr.jpg"))
        assertEquals(Status.Done, both[export.name]); assertEquals(Status.CameraVersion, both[raw.name])
    }

    @Test fun cameraJpgAndItsLightroomExportAreDoneSeparately() {
        val jpg = item("IMG20261114133431.jpg"); val export = item("IMG20261114133431.jpg", path = "Pictures/AdobeLightroom/")
        val r = Classifier.classify(listOf(jpg, export), emptyMap(), emptySet(), setOf(jpg.name), setOf("IMG20261114133431.jpg"), "Pictures/AdobeLightroom/")
        assertEquals(Status.Done, r.first { it.item.id == jpg.id }.status)
        assertEquals(Status.LightroomExport, r.first { it.item.id == export.id }.status)
    }

    @Test fun clearingTheSelectionTurnsEverythingOffAndLeavesWhatIsDoneAlone() {
        val lr = "Pictures/AdobeLightroom/"
        val jpg = item("IMG20261117101010.jpg"); val dng = item("IMG20261117101020.dng")
        val frame = item("IMG_20261117_101111.jpg", taken = 5); val export = item("IMG20261117101020.jpg", path = lr)
        val finished = item("IMG20261116090000.jpg")
        val all = listOf(jpg, dng, frame, export, finished)
        val done = setOf("IMG20261116090000.jpg")
        val before = Classifier.classify(all, emptyMap(), emptySet(), setOf(jpg.name, dng.name), done, lr)
        // Een Galerij-bewerking en een Lightroom-export staan uit zichzelf aan, dus ook zij gaan mee.
        assertEquals(setOf(jpg.name, dng.name, frame.name, export.name), before.filter { it.goesAlong }.map { it.item.name }.toSet())
        // Wat KeeperPrefs.clearSelection doet: de bewaard-lijst leeg, en wat uit zichzelf aanstond op de uitzetlijst.
        val exclude = before.filter { it.goesAlong && it.defaultsOn }.map { it.item.name }.toSet()
        val after = Classifier.classify(all, emptyMap(), exclude, emptySet(), done, lr)
        assertEquals(0, after.count { it.goesAlong })
        assertEquals(Status.Done, after.first { it.item.id == finished.id }.status)
        assertEquals(Status.Skipped, after.first { it.item.id == frame.id }.status)
        // En ze zijn weer aan te zetten, zoals bij een nieuwe foto.
        assertEquals(true, after.first { it.item.id == frame.id }.selectable)
        assertEquals(true, after.first { it.item.id == jpg.id }.selectable)
    }
}
