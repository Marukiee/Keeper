package nl.markmaaktmedia.keeper.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.coroutines.cancellation.CancellationException
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
)

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
        val gain = loadGainMap(source, layout)
        log("gain map ${gain.width}x${gain.height}x${gain.channels}, ${(System.nanoTime() - t0) / 1_000_000} ms")

        // Eerste pass: de piek van de inhoud, op een verkleind beeld.
        val sample = when { w.toLong() * h >= 40_000_000L -> 4; w.toLong() * h >= 8_000_000L -> 2; else -> 1 }
        val first = measure(source, layout, gain, sample, onProgress, cancelled)
        val peak = first.peak
        val space = first.space
        log("piek $peak, kleurruimte $space, ${(System.nanoTime() - t0) / 1_000_000} ms")

        val luma = if (space.contains("P3", ignoreCase = true)) ToneMapper.LUMA_P3 else ToneMapper.LUMA_SRGB
        // De belichting volgt de camera: de foto komt even helder uit als de SDR-versie die de camera zelf maakte (zie ToneMapper.matching).
        val mapper = ToneMapper.matching(settings.toneMap, peak, luma, first.samples, first.sampleCount, first.baseMean)
        val sampler = GainMapSampler(layout.meta, gain, w, h)
        val segments = segmentsFor(layout.primary, layout, space)

        // Vier trappen naast elkaar: een thread decodeert de volgende strook, de pixelstap rekent de huidige over meerdere threads, en het
        // coderen (JPEG, per MCU-rij los) loopt op zijn eigen pool terwijl de volgende strook wordt voorbereid.
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val regionFd = source.open()
        val decoder = try { BitmapRegionDecoder.newInstance(regionFd) } catch (t: Throwable) { regionFd.close(); throw t }
        val pool = Executors.newFixedThreadPool(workers)
        val encodePool = Executors.newFixedThreadPool(workers)
        val encodeThread = Executors.newSingleThreadExecutor()
        val decodeThread = Executors.newSingleThreadExecutor()
        val scratches = List(workers) { Scratch(w, sampler.newScratch()) }
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
                    if (r0 >= r1) null else pool.submit { pixels(raw, rowBytes, y0, r0, r1, w, sampler, mapper, scratches[i], buf) }
                }
                tasks.forEach { it.await() }
                tPixels += System.nanoTime() - t
                encoding[k % 2] = encodeThread.submit { encoder.writeRows(buf, 0, rows) }
                onProgress(0.1f + 0.9f * y1 / h)
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
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("klaar in $ms ms")
        return ConvertResult(w, h, peak, "${gain.width}x${gain.height}x${gain.channels}", space, ms)
    }

    private companion object { const val SAMPLE_STEP = 4 }

    private fun <T> Future<T>.await(): T = try { get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

    private class Scratch(width: Int, val gain: FloatArray) { val sdr = FloatArray(width * 3) }

    /** Rijen [r0, r1) van een strook: SDR uit de bytes, HDR via de gain map, tone map, naar 8-bit sRGB in [out]. */
    private fun pixels(
        raw: ByteArray, rowBytes: Int, stripY: Int, r0: Int, r1: Int, w: Int,
        sampler: GainMapSampler, mapper: ToneMapper, s: Scratch, out: ByteArray,
    ) {
        val px = s.sdr
        for (r in r0 until r1) {
            var p = r * rowBytes
            for (x in 0 until w) {
                px[x * 3] = Srgb.toLinear[raw[p].toInt() and 0xFF]
                px[x * 3 + 1] = Srgb.toLinear[raw[p + 1].toInt() and 0xFF]
                px[x * 3 + 2] = Srgb.toLinear[raw[p + 2].toInt() and 0xFF]
                p += 4
            }
            sampler.hdrRow(stripY + r, px, px, s.gain)
            var o = r * w * 3
            for (x in 0 until w) {
                mapper.map(px[x * 3], px[x * 3 + 1], px[x * 3 + 2], px, x * 3)
                out[o] = Srgb.toByte(px[x * 3]).toByte()
                out[o + 1] = Srgb.toByte(px[x * 3 + 1]).toByte()
                out[o + 2] = Srgb.toByte(px[x * 3 + 2]).toByte()
                o += 3
            }
        }
    }

    // ---- delen ----

    private fun stream(source: JpegSource): InputStream = ParcelFileDescriptor.AutoCloseInputStream(source.open())

    private fun <T> open(source: JpegSource, block: (ParcelFileDescriptor) -> T): T? {
        val fd = source.open()
        return try { block(fd) } finally { fd.close() }
    }

    /** De gain map als bytes: één kanaal als alle pixels grijs zijn, anders drie. */
    private fun loadGainMap(source: JpegSource, layout: UltraHdrLayout): GainMapImage {
        val bytes = ByteArray(layout.gainLength.toInt())
        FileInputStream(source.open().fileDescriptor).use { ins ->
            ins.channel.position(layout.gainStart)
            var got = 0
            while (got < bytes.size) { val n = ins.read(bytes, got, bytes.size - got); if (n < 0) error("gain map afgekapt"); got += n }
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("gain map niet te decoderen")
        val gw = bmp.width
        val gh = bmp.height
        val rowBytes = bmp.rowBytes
        val raw = ByteArray(rowBytes * gh)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        bmp.recycle()
        var gray = true
        loop@ for (y in 0 until gh) {
            var p = y * rowBytes
            for (x in 0 until gw) {
                if (raw[p] != raw[p + 1] || raw[p] != raw[p + 2]) { gray = false; break@loop }
                p += 4
            }
        }
        val ch = if (gray) 1 else 3
        val data = ByteArray(gw * gh * ch)
        for (y in 0 until gh) {
            var p = y * rowBytes
            var o = y * gw * ch
            for (x in 0 until gw) {
                data[o++] = raw[p]
                if (ch == 3) { data[o++] = raw[p + 1]; data[o++] = raw[p + 2] }
                p += 4
            }
        }
        return GainMapImage(gw, gh, ch, data)
    }

    /**
     * Wat de eerste pass over het verkleinde beeld oplevert: de piek, de kleurruimte, en een steekproef van de lineaire HDR-pixels met de
     * gemiddelde luminantie van dezelfde pixels in de SDR-versie van de camera (de basis, voor de gain map erop komt).
     */
    private class Measured(val peak: Float, val space: String, val samples: FloatArray, val sampleCount: Int, val baseMean: Double)

    /** Piek uit het 99,95e percentiel van de luminantie van de gereconstrueerde HDR, op een verkleind beeld. */
    private fun measure(
        source: JpegSource, layout: UltraHdrLayout, gain: GainMapImage, sample: Int,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean,
    ): Measured {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample }
        val bmp = open(source) { fd -> BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opts) } ?: error("verkleind beeld niet te decoderen")
        val space = bmp.colorSpace?.name ?: "onbekend"
        val luma = if (space.contains("P3", ignoreCase = true)) ToneMapper.LUMA_P3 else ToneMapper.LUMA_SRGB
        val bw = bmp.width
        val bh = bmp.height
        val sampler = GainMapSampler(layout.meta, gain, bw, bh)
        val hist = LuminanceHistogram()
        val rowBytes = bmp.rowBytes
        val raw = ByteArray(rowBytes * bh)
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        bmp.recycle()
        val row = FloatArray(bw * 3)
        // Elke vierde pixel van elke vierde rij: een paar honderdduizend punten, genoeg voor een gemiddelde.
        val samples = FloatArray(((bw + SAMPLE_STEP - 1) / SAMPLE_STEP) * ((bh + SAMPLE_STEP - 1) / SAMPLE_STEP) * 3)
        var sampleCount = 0
        var baseSum = 0.0
        for (y in 0 until bh) {
            if (y % 64 == 0) {
                if (cancelled()) throw CancellationException("afgebroken")
                onProgress(0.1f * y / bh)
            }
            var p = y * rowBytes
            for (x in 0 until bw) {
                row[x * 3] = Srgb.toLinear[raw[p].toInt() and 0xFF]
                row[x * 3 + 1] = Srgb.toLinear[raw[p + 1].toInt() and 0xFF]
                row[x * 3 + 2] = Srgb.toLinear[raw[p + 2].toInt() and 0xFF]
                p += 4
            }
            val sampled = y % SAMPLE_STEP == 0
            if (sampled) for (x in 0 until bw step SAMPLE_STEP) baseSum += luma[0] * row[x * 3] + luma[1] * row[x * 3 + 1] + luma[2] * row[x * 3 + 2]
            sampler.hdrRow(y, row, row)
            for (x in 0 until bw) hist.add(luma[0] * row[x * 3] + luma[1] * row[x * 3 + 1] + luma[2] * row[x * 3 + 2])
            if (sampled) for (x in 0 until bw step SAMPLE_STEP) {
                samples[sampleCount * 3] = row[x * 3]; samples[sampleCount * 3 + 1] = row[x * 3 + 1]; samples[sampleCount * 3 + 2] = row[x * 3 + 2]
                sampleCount++
            }
        }
        return Measured(hist.peak(), space, samples, sampleCount, if (sampleCount > 0) baseSum / sampleCount else 0.0)
    }
}
