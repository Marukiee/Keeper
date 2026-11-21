package nl.markmaaktmedia.keeper.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import nl.markmaaktmedia.keeper.convert.ConvertCore.Measured
import nl.markmaaktmedia.keeper.convert.ConvertCore.Scratch
import nl.markmaaktmedia.keeper.scan.ToneMap

/** Een JPEG die telkens opnieuw geopend kan worden (elke aanroep geeft een verse, doorzoekbare descriptor vanaf het begin). */
fun interface JpegSource {
    fun open(): ParcelFileDescriptor
}

class ConvertSettings(
    /** libjpeg-kwaliteit 1 tot 100 voor de eigen encoder; het gekozen "q94" van de gebruiker wordt hier vooraf op afgebeeld. */
    val jpegQuality: Int,
    val toneMap: ToneMap,
    val chroma420: Boolean = true,
    /** HDR-sterkte, 0 tot 1 (zie [GainMapSampler]); 1 is de volle HDR van de camera, zoals het altijd was. */
    val strength: Float = 1f,
    /** HDR behouden (zie [Converter.keepHdr]) in plaats van naar SDR omzetten. Bij sterkte 0 is er niets te behouden en wordt het toch SDR. */
    val keepHdr: Boolean = false,
) {
    fun withStrength(value: Float) = ConvertSettings(jpegQuality, toneMap, chroma420, value.coerceIn(0f, 1f), keepHdr)

    /** Dezelfde instellingen met HDR behouden aan of uit, voor één foto die afwijkt van de instelling. */
    fun withKeep(value: Boolean) = ConvertSettings(jpegQuality, toneMap, chroma420, strength, value)

    /** Of dit een Ultra HDR-bestand wordt: aan gezet, en met een sterkte die iets overlaat. */
    val keepsHdr: Boolean get() = keepHdr && strength > 0f
}

class ConvertResult(val width: Int, val height: Int, val peak: Float, val gainMapSize: String, val colorSpace: String, val millis: Long)

class NoGainMapException : Exception("Geen Ultra HDR-gain map in dit bestand")

/**
 * HDR-JPEG (Ultra HDR) naar SDR-JPEG: gain map toepassen, tone map, opnieuw coderen. Alles in stroken van een paar honderd
 * rijen, dus een foto van 200 MP past in het geheugen. De uitvoer krijgt het ICC-profiel en de segmenten uit [segments];
 * de rest van de metadata (EXIF, hdrgm eruit) regelt de aanroeper, zie `MetadataWriter`.
 */
class Converter(private val stripRows: Int = 256, private val log: (String) -> Unit = {}) {

    fun convert(
        source: JpegSource,
        out: OutputStream,
        settings: ConvertSettings,
        segmentsFor: (JpegHead, UltraHdrLayout, String) -> List<ByteArray>,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): ConvertResult {
        val t0 = System.nanoTime()
        val layout = UltraHdrReader.read { stream(source) } ?: throw NoGainMapException()
        val w = layout.primary.width
        val h = layout.primary.height
        val gain = ConvertCore.loadGainMap(source, layout)
        log("gain map ${gain.width}x${gain.height}x${gain.channels}, ${(System.nanoTime() - t0) / 1_000_000} ms")

        // Eerste pass: de piek van de inhoud, op een verkleind beeld.
        val sample = when { w.toLong() * h >= 40_000_000L -> 4; w.toLong() * h >= 8_000_000L -> 2; else -> 1 }
        val first = measure(source, layout, gain, sample, settings.strength, onProgress, cancelled)
        val space = first.space
        val prepared = ConvertCore.prepare(first, settings, sample, w, h)
        val peak = prepared.peak
        val mapper = prepared.mapper
        val tone = prepared.tone
        log("piek $peak, kleurruimte $space, ${(System.nanoTime() - t0) / 1_000_000} ms")
        val sampler = GainMapSampler(layout.meta, gain, w, h, settings.strength)
        val segments = segmentsFor(layout.primary, layout, space)

        val scratches = List(workerCount) { Scratch(w, sampler.newScratch()) }
        encodeStrips(source, w, h, settings, segments, out, 0.1f, onProgress, cancelled) { raw, rowBytes, y0, r0, r1, worker, buf ->
            ConvertCore.pixels(raw, rowBytes, y0, r0, r1, w, sampler, mapper, tone, scratches[worker], buf)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("klaar in $ms ms")
        return ConvertResult(w, h, peak, "${gain.width}x${gain.height}x${gain.channels}", space, ms)
    }

    /** Vult rijen [r0, r1) van een strook (die begint op rij [y0] van het beeld) met 8-bit RGB in [out], vanaf rij [r0] van die strook; [worker] is het nummer van de thread, 0 tot het aantal threads. */
    private fun interface StripFill {
        fun fill(raw: ByteArray, rowBytes: Int, y0: Int, r0: Int, r1: Int, worker: Int, out: ByteArray)
    }

    private val workerCount: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * De stroken van het beeld: decoderen, [fill] erover, coderen naar [out] met [segments] in de kop. Voortgang loopt van [progressBase] tot 1.
     *
     * Vier trappen naast elkaar: een thread decodeert de volgende strook, de pixelstap rekent de huidige over meerdere threads, en het
     * coderen (JPEG, per MCU-rij los) loopt op zijn eigen pool terwijl de volgende strook wordt voorbereid.
     */
    private fun encodeStrips(
        source: JpegSource, w: Int, h: Int, settings: ConvertSettings, segments: List<ByteArray>, out: OutputStream,
        progressBase: Float, onProgress: (Float) -> Unit, cancelled: () -> Boolean, fill: StripFill,
    ) {
        val workers = workerCount
        val regionFd = source.open()
        val decoder = try { BitmapRegionDecoder.newInstance(regionFd) } catch (t: Throwable) { regionFd.close(); throw t }
        val pool = Executors.newFixedThreadPool(workers)
        val encodePool = Executors.newFixedThreadPool(workers)
        val encodeThread = Executors.newSingleThreadExecutor()
        val decodeThread = Executors.newSingleThreadExecutor()
        val buffers = Array(2) { ByteArray(w * 3 * stripRows) }
        val encoding = arrayOfNulls<Future<*>>(2)
        // Twee ruwe stroken: de ene wordt gedecodeerd terwijl de andere gerekend wordt.
        val raws = Array(2) { ByteArray(0) }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val strips = (h + stripRows - 1) / stripRows
        var upcoming: Future<Int>? = null
        try {
            val encoder = JpegEncoder(out, w, h, settings.jpegQuality, settings.chroma420, segments, encodePool)
            /** Decodeert strook [k] in [raws] en geeft de rijlengte in bytes terug. */
            fun decodeStrip(k: Int): Future<Int> = decodeThread.submit<Int> {
                val y0 = k * stripRows
                val y1 = minOf(y0 + stripRows, h)
                val bmp = decoder.decodeRegion(Rect(0, y0, w, y1), opts) ?: error("strook $y0 niet te decoderen")
                check(bmp.width == w && bmp.height == y1 - y0) { "strook heeft ${bmp.width}x${bmp.height}, verwacht ${w}x${y1 - y0}" }
                val rowBytes = bmp.rowBytes
                if (raws[k % 2].size < rowBytes * bmp.height) raws[k % 2] = ByteArray(rowBytes * bmp.height)
                bmp.copyPixelsToBuffer(ByteBuffer.wrap(raws[k % 2]))
                bmp.recycle()
                rowBytes
            }
            var tWait = 0L; var tPixels = 0L; var tEncoder = 0L
            upcoming = decodeStrip(0)
            for (k in 0 until strips) {
                if (cancelled()) throw CancellationException("afgebroken")
                val y0 = k * stripRows
                val y1 = minOf(y0 + stripRows, h)
                var t = System.nanoTime()
                val rowBytes = upcoming!!.await()
                // Pas nu, als strook k klaar is, mag strook k + 1 in de andere buffer beginnen.
                upcoming = if (k + 1 < strips) decodeStrip(k + 1) else null
                tWait += System.nanoTime() - t; t = System.nanoTime()
                val raw = raws[k % 2]
                val buf = buffers[k % 2]
                encoding[k % 2]?.await() // de vorige keer dat deze buffer naar de encoder ging moet klaar zijn
                tEncoder += System.nanoTime() - t; t = System.nanoTime()
                val rows = y1 - y0
                val chunk = (rows + workers - 1) / workers
                val tasks = (0 until workers).mapNotNull { i ->
                    val r0 = i * chunk
                    val r1 = minOf(r0 + chunk, rows)
                    if (r0 >= r1) null else pool.submit { fill.fill(raw, rowBytes, y0, r0, r1, i, buf) }
                }
                tasks.forEach { it.await() }
                tPixels += System.nanoTime() - t
                encoding[k % 2] = encodeThread.submit { encoder.writeRows(buf, 0, rows) }
                onProgress(progressBase + (1f - progressBase) * y1 / h)
            }
            encoding.forEach { it?.await() }
            encoder.finish()
            log("tijd: wachten op decoderen ${tWait / 1_000_000} ms, pixels ${tPixels / 1_000_000} ms, wachten op encoder ${tEncoder / 1_000_000} ms")
        } finally {
            // Eerst laten uitlopen wat nog decodeert: de decoder mag niet vrijgegeven worden terwijl een thread hem nog gebruikt.
            decodeThread.shutdown()
            runCatching { upcoming?.get() }
            decodeThread.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            pool.shutdownNow()
            encodeThread.shutdownNow()
            encodePool.shutdownNow()
            decoder.recycle()
            regionFd.close()
        }
    }

    /**
     * HDR behouden: de foto blijft een Ultra HDR-bestand. Het hoofdbeeld (de SDR van de camera, pixel voor pixel) wordt opnieuw gecodeerd
     * met [ConvertSettings.jpegQuality], de gain map gaat er ongewijzigd achter aan, met de HDR-sterkte [ConvertSettings.strength] in de XMP en
     * het ISO-blok (zie [GainMapMeta.scaled]). Er is geen meetpass en geen tone map nodig, dus dit is veel sneller dan omzetten naar SDR.
     *
     * De MPF-directory moet de lengte van het hoofdbeeld weten, die pas bekend is als het gecodeerd is: het hoofdbeeld gaat daarom eerst naar
     * [work], de MPF wordt daarin ingevuld, en dan volgt alles in één keer naar [out]. [work] wordt daarna weggehaald.
     */
    fun keepHdr(
        source: JpegSource,
        out: OutputStream,
        settings: ConvertSettings,
        work: File,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): ConvertResult {
        val t0 = System.nanoTime()
        val layout = UltraHdrReader.read { stream(source) } ?: throw NoGainMapException()
        val w = layout.primary.width
        val h = layout.primary.height
        val gain = UltraHdrWriter.rewriteGainMap(ConvertCore.gainBytes(source, layout), layout.meta, settings.strength)
        val header = OutputMetadata.ultraHdr(layout.primary, gain.size)
        try {
            work.parentFile?.mkdirs()
            work.outputStream().buffered(256 * 1024).use { temp ->
                encodeStrips(source, w, h, settings, header.segments, temp, 0f, { onProgress(it * 0.95f) }, cancelled) { raw, rowBytes, _, r0, r1, _, buf ->
                    ConvertCore.rgb(raw, rowBytes, r0, r1, w, buf)
                }
            }
            val primaryLength = work.length()
            RandomAccessFile(work, "rw").use { f ->
                f.seek(header.mpfOffset)
                f.write(UltraHdrWriter.mpf(primaryLength, header.mpfOffset, gain.size.toLong()))
            }
            work.inputStream().use { it.copyTo(out, 256 * 1024) }
            out.write(gain)
            out.flush()
        } finally {
            work.delete()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("HDR behouden in $ms ms, sterkte ${settings.strength}, gain map ${gain.size} bytes")
        return ConvertResult(w, h, 2f.pow(layout.meta.max.max() * settings.strength), "${layout.gain.width}x${layout.gain.height}", "ongewijzigd", ms)
    }

    private fun <T> Future<T>.await(): T = try { get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

    // ---- delen ----

    private fun stream(source: JpegSource): InputStream = ParcelFileDescriptor.AutoCloseInputStream(source.open())

    private fun <T> open(source: JpegSource, block: (ParcelFileDescriptor) -> T): T? {
        val fd = source.open()
        return try { block(fd) } finally { fd.close() }
    }

    /** De eerste pass: het beeld op 1/[sample] decoderen en meten (zie [ConvertCore.analyse]). */
    private fun measure(
        source: JpegSource, layout: UltraHdrLayout, gain: GainMapImage, sample: Int, strength: Float,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean,
    ): Measured {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample }
        val bmp = open(source) { fd -> BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opts) } ?: error("verkleind beeld niet te decoderen")
        val space = bmp.colorSpace?.name ?: "onbekend"
        val bw = bmp.width
        val bh = bmp.height
        val rowBytes = bmp.rowBytes
        val raw = ByteArray(rowBytes * bh)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        bmp.recycle()
        return ConvertCore.analyse(raw, rowBytes, bw, bh, space, layout.meta, gain, strength, onProgress, cancelled)
    }
}
