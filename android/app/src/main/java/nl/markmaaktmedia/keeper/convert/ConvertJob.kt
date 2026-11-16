package nl.markmaaktmedia.keeper.convert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.keeper.data.KeeperPrefs
import nl.markmaaktmedia.keeper.scan.Classifier
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.ToneMap
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Alleen voor de debug-ontvanger (zie `ConvertDebugReceiver`), zodat de hele keten op de telefoon te testen is zonder de selectie van Mark
 * naar de echte uitvoermap te sturen: [only] beperkt de run tot die MediaStore-ids (ze tellen dan als bewaard), [folder] vervangt de
 * uitvoermap. [readOriginal] uit leest zonder `setRequireOriginal`, voor als de locatie-toestemming ontbreekt; de uitvoer heeft dan geen GPS.
 */
class ConvertOptions(val only: Set<Long>? = null, val folder: String? = null, val readOriginal: Boolean = true)

/** Waar de run mee bezig is. [index] telt vanaf 1; [fraction] is hoever die ene foto is (0 tot 1). */
data class ConvertProgress(val index: Int, val total: Int, val name: String, val fraction: Float)

class ConvertFailure(val name: String, val reason: String)

/** Wat de run deed. [problem] is gezet als hij niet eens kon beginnen. */
class ConvertReport(
    val converted: Int = 0,
    val copied: Int = 0,
    /** Overgeslagen omdat de naam er al stond of dubbel was; geen fout. */
    val skipped: Int = 0,
    val failures: List<ConvertFailure> = emptyList(),
    val cancelled: Boolean = false,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val problem: Problem? = null,
) {
    enum class Problem { NoLocationAccess }
    val done get() = converted + copied
    val savedBytes get() = (bytesIn - bytesOut).coerceAtLeast(0)
}

/**
 * Eén run: leest wat nu meegaat, zet het in de uitvoermap en geeft verslag. Doet niets met de originelen behalve ze lezen.
 *
 * Elke lezing gaat via [openOriginal]: MediaStore haalt de locatie uit elk bestand dat je gewoon opent, zonder fout. Met
 * ACCESS_MEDIA_LOCATION en `setRequireOriginal` krijg je het bestand zoals de camera het schreef, en dat is wat we nodig hebben.
 */
class ConvertJob(private val context: Context, private val prefs: KeeperPrefs, private val scanner: MediaScanner) {
    private val resolver = context.contentResolver
    private val store = OutputStore(resolver)
    private val work = File(context.cacheDir, "convert")

    /** Of lezen via `setRequireOriginal` gaat (altijd, behalve in een debug-test zonder locatie-toestemming). */
    private var requireOriginal = true

    suspend fun run(onProgress: (ConvertProgress) -> Unit, stopRequested: () -> Boolean, options: ConvertOptions = ConvertOptions()): ConvertReport = withContext(Dispatchers.IO) {
        requireOriginal = options.readOriginal
        if (options.readOriginal && context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ConvertReport(problem = ConvertReport.Problem.NoLocationAccess)
        }
        val job = coroutineContext.job
        val cancelled = { stopRequested() || !job.isActive }

        val snapshot = scanner.scan()
        val only = options.only
        // Een test telt zijn foto's als bewaard, zonder iets in de instellingen te veranderen.
        val kept = prefs.cameraVersion.first() + if (only == null) emptySet() else snapshot.items.filter { it.id in only }.map { it.name }
        // In een testmap zegt wat er in de echte uitvoermap staat niets.
        val done = if (options.folder == null) snapshot.doneNames else emptySet()
        val photos = Classifier.classify(snapshot.items, snapshot.probes, prefs.skipped.first(), kept, done, snapshot.lightroomFolder, prefs.rejected.first())
            .filter { only == null || it.item.id in only }
        val folder = OutputStore.folder(options.folder ?: prefs.outputFolder.first())
        val plan = ConvertPlan.of(photos, snapshot.lightroomFolder, done)
        val settings = ConvertSettings(
            // Het gekozen "q94" is jpegli; onze encoder is libjpeg en geeft bij twee lager dezelfde grootte en kwaliteit (gemeten in fase 2).
            jpegQuality = (prefs.quality.first() - 2).coerceIn(50, 98),
            toneMap = ToneMap.of(prefs.toneMap.first()),
        )

        work.deleteRecursively(); work.mkdirs()
        store.removeLeftovers(folder)

        var converted = 0
        var copied = 0
        var skipped = plan.existing.size
        var bytesIn = 0L
        var bytesOut = 0L
        var stopped = false
        val failures = ArrayList<ConvertFailure>()
        plan.clashes.forEach { failures += ConvertFailure(it.item.name, "naam al in gebruik") }

        for ((i, step) in plan.steps.withIndex()) {
            if (cancelled()) { stopped = true; break }
            val name = step.photo.item.name
            onProgress(ConvertProgress(i + 1, plan.steps.size, name, 0f))
            try {
                val result = writeOne(step, folder, settings, cancelled) { onProgress(ConvertProgress(i + 1, plan.steps.size, name, it)) }
                if (step.how == How.Copy || result.copiedAsIs) copied++ else converted++
                bytesIn += result.bytesIn
                bytesOut += result.bytesOut
            } catch (_: AlreadyThere) {
                skipped++
            } catch (e: CancellationException) {
                // Door ons gestopt, of de hele taak is geannuleerd: dan niet verder en de annulering doorgeven.
                if (!job.isActive) throw e
                stopped = true
                break
            } catch (e: Exception) {
                failures += ConvertFailure(name, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
        work.deleteRecursively()
        ConvertReport(converted, copied, skipped, failures, stopped, bytesIn, bytesOut)
    }

    /** De naam stond er net al (een andere run of app was sneller): overslaan, geen fout. */
    private class AlreadyThere : Exception()

    private class Written(val bytesIn: Long, val bytesOut: Long, val copiedAsIs: Boolean)

    private fun writeOne(step: Step, folder: String, settings: ConvertSettings, cancelled: () -> Boolean, progress: (Float) -> Unit): Written {
        val item = step.photo.item
        // Net voor het schrijven nog eens kijken: een andere run of app kan de naam intussen hebben gemaakt.
        if (store.exists(folder, step.outName)) throw AlreadyThere()
        val mime = if (step.how == How.Copy) item.mime else "image/jpeg"
        val target = store.create(folder, step.outName, mime, item.dateTakenMs)
        try {
            val pfd = resolver.openFileDescriptor(target, "w") ?: throw java.io.IOException("kan ${step.outName} niet openen om te schrijven")
            // De stroom bezit de descriptor en sluit hem; zo wordt hij maar één keer gesloten.
            val counted = CountingOutput(ParcelFileDescriptor.AutoCloseOutputStream(pfd).buffered(256 * 1024))
            val written = counted.use { out ->
                when (step.how) {
                    How.Copy -> copyOriginal(item.id, item.size, out, progress)
                    How.Convert -> convert(JpegSource { openOriginal(item.id) }, item.size, out, settings, cancelled, progress)
                    How.CameraVersion -> cameraVersion(item.id, out, settings, cancelled, progress)
                }
            }
            if (counted.count == 0L) throw java.io.IOException("leeg resultaat")
            store.finish(target)
            return Written(written.bytesIn, counted.count, written.copiedAsIs)
        } catch (e: Throwable) {
            store.abort(target)
            throw e
        }
    }

    private fun openOriginal(id: Long): ParcelFileDescriptor {
        val uri = MediaScanner.imageUri(id)
        return resolver.openFileDescriptor(if (requireOriginal) MediaStore.setRequireOriginal(uri) else uri, "r") ?: throw java.io.IOException("kan het origineel niet openen")
    }

    private fun copyOriginal(id: Long, size: Long, out: OutputStream, progress: (Float) -> Unit): Written {
        ParcelFileDescriptor.AutoCloseInputStream(openOriginal(id)).use { copy(it, out, size, progress) }
        return Written(size, 0, true)
    }

    /** SDR uit een Ultra HDR-JPEG; zonder gain map (de kop zei anders) gaat het bestand zoals het is. */
    private fun convert(source: JpegSource, size: Long, out: OutputStream, settings: ConvertSettings, cancelled: () -> Boolean, progress: (Float) -> Unit): Written =
        try {
            Converter().convert(source, out, settings, { head, _, _ -> OutputMetadata.segments(head) }, progress, cancelled)
            Written(size, 0, false)
        } catch (_: NoGainMapException) {
            ParcelFileDescriptor.AutoCloseInputStream(source.open()).use { copy(it, out, size, progress) }
            Written(size, 0, true)
        }

    /** De JPEG in IFD0 van de DNG naar een tijdelijk bestand en dan als elke andere HDR-JPEG. */
    private fun cameraVersion(id: Long, out: OutputStream, settings: ConvertSettings, cancelled: () -> Boolean, progress: (Float) -> Unit): Written {
        val temp = File(work, "$id.jpg")
        try {
            val length = ParcelFileDescriptor.AutoCloseInputStream(openOriginal(id)).use { input ->
                val range = DngPreview.find(input.channel) ?: throw java.io.IOException("geen camera-versie in deze DNG")
                temp.outputStream().buffered(256 * 1024).use { o -> copyRange(input, range, o) }
                range.length
            }
            return convert(JpegSource { ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY) }, length, out, settings, cancelled, progress)
        } finally {
            temp.delete()
        }
    }

    private fun copyRange(input: FileInputStream, range: DngPreview.Range, out: OutputStream) {
        val channel = input.channel
        channel.position(range.offset)
        val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
        var left = range.length
        while (left > 0) {
            buffer.clear()
            if (left < buffer.capacity()) buffer.limit(left.toInt())
            val read = channel.read(buffer)
            if (read < 0) throw java.io.IOException("de DNG is korter dan zijn preview")
            out.write(buffer.array(), 0, read)
            left -= read
        }
    }

    private fun copy(input: java.io.InputStream, out: OutputStream, size: Long, progress: (Float) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
            if (size > 0) progress((done.toFloat() / size).coerceIn(0f, 1f))
        }
    }

    private class CountingOutput(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}
