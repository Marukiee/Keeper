package nl.markmaaktmedia.keeper.convert

import android.Manifest
import android.content.ContentUris
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
import nl.markmaaktmedia.keeper.scan.MediaItem
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.OutputName
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
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
 *
 * Ook voor "alleen deze foto omzetten" in de viewer: [only] zet die ene foto om zonder dat hij bewaard hoeft te zijn of te worden, en met
 * [newVersion] komt er een tweede versie naast als de eerste al in de uitvoermap staat (naam met `-2`), zodat ze te vergelijken zijn.
 */
class ConvertOptions(
    val only: Set<Long>? = null, val folder: String? = null, val readOriginal: Boolean = true, val newVersion: Boolean = false,
    /** Alleen voor de debug-ontvanger: HDR behouden aan of uit, los van de instelling. Null is wat in de instellingen staat. */
    val keepHdr: Boolean? = null,
    /** Alleen voor de debug-ontvanger: de originelen van deze run toch voor verbergen klaarzetten, ook bij een test of "alleen deze foto". */
    val forceHide: Boolean = false,
)

/** Waar de run mee bezig is. [index] telt vanaf 1; [fraction] is hoever die ene foto is (0 tot 1). [item] en [how] zijn voor het scherm: welke foto, en wat ermee gebeurt. */
data class ConvertProgress(val index: Int, val total: Int, val name: String, val fraction: Float, val item: MediaItem? = null, val how: How? = null, val alreadyDone: Int = 0)

class ConvertFailure(val name: String, val reason: String)

/** Wat de run deed. [problem] is gezet als hij niet eens kon beginnen. */
class ConvertReport(
    val converted: Int = 0,
    val copied: Int = 0,
    /** Overgeslagen omdat de naam er al stond of dubbel was; geen fout. */
    val skipped: Int = 0,
    val failures: List<ConvertFailure> = emptyList(),
    /** Gepauzeerd op verzoek: wat nog niet aan de beurt was, staat in [left] en komt bij Doorgaan gewoon weer aan de beurt. */
    val paused: Boolean = false,
    /** Hoeveel foto's er nog te doen stonden toen hij pauzeerde (de foto waar hij mee bezig was telt mee). */
    val left: Int = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val problem: Problem? = null,
    /** Hoe de nieuwe bestanden heten (voor het verslag). */
    val written: List<String> = emptyList(),
    /** Van zoveel originelen is de uitvoer klaar en staan ze klaar om verborgen te worden (zie [OriginalsHider]); alleen als dat aanstaat. */
    val hideQueued: Int = 0,
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
        val onlyNames = if (only == null) emptySet() else snapshot.items.filter { it.id in only }.map { it.name }.toSet()
        val kept = prefs.cameraVersion.first() + onlyNames
        // Een foto die Mark zelf uitzette telt voor "alleen deze" toch mee: dat is wat hij nu vraagt.
        val skippedNames = prefs.skipped.first() - onlyNames
        // In een testmap zegt wat er in de echte uitvoermap staat niets.
        val existing = if (options.folder == null) snapshot.doneNames else emptySet()
        // Een nieuwe versie begint bij een foto die al klaar is: voor de indeling telt dat niet, voor de namen wel.
        val done = if (options.newVersion) emptySet() else existing
        val photos = Classifier.classify(snapshot.items, snapshot.probes, skippedNames, kept, done, snapshot.lightroomFolder, prefs.rejected.first())
            .filter { only == null || it.item.id in only }
        val folder = OutputStore.folder(options.folder ?: prefs.outputFolder.first())
        val settings = ConvertSettings(
            // Het gekozen "q94" is jpegli; onze encoder is libjpeg en geeft bij twee lager dezelfde grootte en kwaliteit (gemeten in fase 2).
            jpegQuality = (prefs.quality.first() - 2).coerceIn(50, 98),
            toneMap = ToneMap.of(prefs.toneMap.first()),
            keepHdr = options.keepHdr ?: prefs.keepHdr.first(),
        )
        // De HDR-sterkte: de standaard uit Instellingen, of wat Mark voor deze ene foto instelde (per bestandsnaam van het origineel).
        val defaultStrength = prefs.hdrStrength.first()
        val strengthByName = prefs.hdrStrengthByName.first()
        val strengthOf = { photo: Photo -> (strengthByName[photo.item.name] ?: defaultStrength) / 100f }
        // Wat omgezet wordt, krijgt `-SDR` of (HDR behouden) `-HDR` in de naam. Dat hangt per foto af van de instelling én de sterkte (sterkte 0 valt terug op SDR),
        // dus de planning vraagt het hier.
        val plan = ConvertPlan.of(photos, snapshot.lightroomFolder, existing, options.newVersion) { photo ->
            if (settings.withStrength(strengthOf(photo)).keepsHdr) OutputName.Tag.Hdr else OutputName.Tag.Sdr
        }
        // Originelen verbergen (instelling): alleen na een hele run telt wat klaar is, zie OriginalsHiding.eligible. De controle zelf komt later, vlak voor het verbergen.
        val hideOn = prefs.hideOriginals.first()
        val wholeRun = options.forceHide || (options.only == null && options.folder == null && !options.newVersion)

        work.deleteRecursively(); work.mkdirs()
        store.removeLeftovers(folder)

        var converted = 0
        var copied = 0
        var hideQueued = 0
        val written = ArrayList<String>()
        // Wat al in de uitvoermap staat, is eerder gedaan en blijft met rust; wat Mark daaruit verwijderd heeft, is hier geen Klaar meer en komt dus weer aan de beurt.
        val alreadyDone = photos.count { it.status == Status.Done }
        var skipped = plan.existing.size + alreadyDone
        var bytesIn = 0L
        var bytesOut = 0L
        var stopped = false
        var left = 0
        val failures = ArrayList<ConvertFailure>()
        plan.clashes.forEach { failures += ConvertFailure(it.item.name, "naam al in gebruik") }

        for ((i, step) in plan.steps.withIndex()) {
            if (cancelled()) { stopped = true; left = plan.steps.size - i; break }
            val name = step.photo.item.name
            onProgress(ConvertProgress(i + 1, plan.steps.size, name, 0f, step.photo.item, step.how, alreadyDone))
            try {
                val strength = strengthOf(step.photo)
                val result = writeOne(step, folder, settings.withStrength(strength), cancelled) { onProgress(ConvertProgress(i + 1, plan.steps.size, name, it, step.photo.item, step.how, alreadyDone)) }
                if (step.how == How.Copy || result.copiedAsIs) copied++ else converted++
                bytesIn += result.bytesIn
                bytesOut += result.bytesOut
                written += step.outName
                if (hideOn && OriginalsHiding.eligible(step.photo, step.how, wholeRun)) {
                    val item = step.photo.item
                    val keptHdr = step.how == How.Convert && !result.copiedAsIs && settings.withStrength(strength).keepsHdr
                    prefs.addPendingHide(listOf(HideEntry(item.id, item.name, item.size, result.outputId, result.bytesOut, keptHdr).encode()))
                    hideQueued++
                }
            } catch (_: AlreadyThere) {
                skipped++
            } catch (e: CancellationException) {
                // Door ons gestopt, of de hele taak is geannuleerd: dan niet verder en de annulering doorgeven.
                if (!job.isActive) throw e
                stopped = true
                left = plan.steps.size - i
                break
            } catch (e: Exception) {
                failures += ConvertFailure(name, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
        work.deleteRecursively()
        ConvertReport(converted, copied, skipped, failures, stopped, left, bytesIn, bytesOut, written = written, hideQueued = hideQueued)
    }

    /** De naam stond er net al (een andere run of app was sneller): overslaan, geen fout. */
    private class AlreadyThere : Exception()

    private class Written(val bytesIn: Long, val bytesOut: Long, val copiedAsIs: Boolean, val outputId: Long = -1)

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
            return Written(written.bytesIn, counted.count, written.copiedAsIs, ContentUris.parseId(target))
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

    /**
     * SDR uit een Ultra HDR-JPEG, of met HDR behouden de Ultra HDR-JPEG opnieuw gecodeerd; zonder gain map (de kop zei anders) gaat het bestand
     * zoals het is.
     */
    private fun convert(source: JpegSource, size: Long, out: OutputStream, settings: ConvertSettings, cancelled: () -> Boolean, progress: (Float) -> Unit): Written =
        try {
            val converter = Converter(log = { android.util.Log.d("KeeperConvert", it) })
            if (settings.keepsHdr) converter.keepHdr(source, out, settings, File(work, "keep-hdr.jpg"), progress, cancelled)
            else converter.convert(source, out, settings, { head, _, _ -> OutputMetadata.segments(head) }, progress, cancelled)
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
