package nl.markmaaktmedia.keeper.convert

import nl.markmaaktmedia.keeper.scan.MediaItem
import nl.markmaaktmedia.keeper.scan.OutputName
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertPlanTest {
    private val lightroom = "Pictures/AdobeLightroom/"
    private var next = 1L

    private fun item(name: String, path: String = "DCIM/Camera/", taken: Long = 1_000, mime: String? = null) =
        MediaItem(next++, name, path, 5_000_000, 1_000, taken, 4000, 3000, mime ?: if (name.endsWith(".dng")) "image/x-adobe-dng" else "image/jpeg")

    private fun photo(item: MediaItem, status: Status, hdr: Boolean = false) = Photo(item, status, hdr)

    @Test fun namesAreFixedPerKindOfSource() {
        assertEquals("IMG1.jpg", OutputName.of(item("IMG1.jpg"), lightroom))
        assertEquals("IMG1.jpg", OutputName.of(item("IMG1.dng"), lightroom))
        assertEquals("IMG1-lr.jpg", OutputName.of(item("IMG1.jpg", path = lightroom), lightroom))
        assertEquals("IMG_20261114_120713.jpg", OutputName.of(item("IMG_20261114_120713.jpg"), lightroom))
        assertEquals("edit-lr.png", OutputName.of(item("edit.png", path = lightroom, mime = "image/png"), lightroom))
    }

    @Test fun aNewVersionGetsTheNextFreeName() {
        assertEquals("IMG1.jpg", OutputName.versioned("IMG1.jpg", emptySet()))
        assertEquals("IMG1-2.jpg", OutputName.versioned("IMG1.jpg", setOf("img1.jpg")))
        assertEquals("IMG1-3.jpg", OutputName.versioned("IMG1.jpg", setOf("img1.jpg", "img1-2.jpg")))
        assertEquals("edit-lr-2.png", OutputName.versioned("edit-lr.png", setOf("edit-lr.png")))
        assertEquals("noext-2", OutputName.versioned("noext", setOf("noext")))
    }

    @Test fun withoutNewVersionAPhotoThatIsAlreadyThereIsSkipped() {
        val a = photo(item("IMG1.jpg"), Status.Convert, hdr = true)
        val plan = ConvertPlan.of(listOf(a), lightroom, setOf("IMG1.jpg"))
        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.existing.size)
    }

    @Test fun aNewVersionIsWrittenNextToTheFirstOne() {
        val a = photo(item("IMG1.jpg"), Status.Convert, hdr = true)
        val plan = ConvertPlan.of(listOf(a), lightroom, setOf("IMG1.jpg"), newVersion = true)
        assertEquals(listOf("IMG1-2.jpg"), plan.steps.map { it.outName })
        assertEquals(How.Convert, plan.steps.single().how)
        assertTrue(plan.existing.isEmpty() && plan.clashes.isEmpty())
        // En nog een keer, als de tweede er ook al staat.
        val again = ConvertPlan.of(listOf(a), lightroom, setOf("IMG1.jpg", "img1-2.jpg"), newVersion = true)
        assertEquals(listOf("IMG1-3.jpg"), again.steps.map { it.outName })
    }

    @Test fun aNewVersionOfAFreshPhotoKeepsTheNormalName() {
        val a = photo(item("IMG2.jpg"), Status.Convert, hdr = true)
        assertEquals(listOf("IMG2.jpg"), ConvertPlan.of(listOf(a), lightroom, setOf("IMG1.jpg"), newVersion = true).steps.map { it.outName })
    }

    @Test fun rawAndItsLightroomExportBothGoAlongUnderDifferentNames() {
        val raw = photo(item("IMG1.dng"), Status.CameraVersion)
        val export = photo(item("IMG1.jpg", path = lightroom), Status.LightroomExport)
        val plan = ConvertPlan.of(listOf(raw, export), lightroom, emptySet())
        assertEquals(setOf("IMG1.jpg", "IMG1-lr.jpg"), plan.steps.map { it.outName }.toSet())
        assertTrue(plan.clashes.isEmpty())
    }

    @Test fun howDependsOnTheStatus() {
        val steps = ConvertPlan.of(
            listOf(
                photo(item("A.jpg"), Status.Convert, hdr = true),
                photo(item("B.jpg"), Status.AsIs),
                photo(item("C.dng"), Status.CameraVersion),
                photo(item("IMG_20261114_120713.jpg"), Status.GalleryEdit, hdr = true),
                photo(item("IMG_20261114_120714.jpg"), Status.GalleryEdit),
                photo(item("D.jpg", path = lightroom), Status.LightroomExport),
            ),
            lightroom, emptySet(),
        ).steps.associate { it.photo.item.name to it.how }
        assertEquals(How.Convert, steps["A.jpg"]); assertEquals(How.Copy, steps["B.jpg"]); assertEquals(How.CameraVersion, steps["C.dng"])
        assertEquals(How.Convert, steps["IMG_20261114_120713.jpg"]); assertEquals(How.Copy, steps["IMG_20261114_120714.jpg"])
        assertEquals(How.Copy, steps["D.jpg"])
    }

    @Test fun onlyWhatGoesAlongIsPlannedOldestFirst() {
        val newer = photo(item("N.jpg", taken = 2_000), Status.AsIs)
        val older = photo(item("O.jpg", taken = 1_000), Status.AsIs)
        val plan = ConvertPlan.of(listOf(newer, older, photo(item("X.jpg"), Status.NotKept), photo(item("Y.dng"), Status.NeedsEdit)), lightroom, emptySet())
        assertEquals(listOf("O.jpg", "N.jpg"), plan.steps.map { it.photo.item.name })
    }

    @Test fun anExistingNameIsSkippedNeverWrittenTwice() {
        val a = photo(item("A.jpg"), Status.AsIs); val b = photo(item("B.jpg"), Status.AsIs)
        val plan = ConvertPlan.of(listOf(a, b), lightroom, setOf("a.JPG"))
        assertEquals(listOf("B.jpg"), plan.steps.map { it.outName }); assertEquals(listOf(a), plan.existing)
    }

    @Test fun twoPhotosWithOneNameKeepTheFirstAndReportTheSecond() {
        // Dezelfde naam in twee mappen van de camera: de tweede wordt gemeld, niet "(1)" genoemd.
        val a = photo(item("A.jpg", taken = 1_000), Status.AsIs)
        val b = photo(item("A.jpg", path = "DCIM/Camera/Cshot/", taken = 2_000), Status.AsIs)
        val plan = ConvertPlan.of(listOf(a, b), lightroom, emptySet())
        assertEquals(1, plan.steps.size); assertEquals(listOf(b), plan.clashes)
    }
}
