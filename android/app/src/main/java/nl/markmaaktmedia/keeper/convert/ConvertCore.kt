package nl.markmaaktmedia.keeper.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * De rekenstappen die [Converter] (de echte omzetting, in stroken) en [ConvertPreview] (het voorbeeld in de viewer, op een verkleind beeld)
 * samen gebruiken, zodat het voorbeeld precies is wat de omzetting straks maakt.
 */
internal object ConvertCore {
    /** Het steekproefraster: elke vierde pixel van elke vierde rij. */
    const val SAMPLE_STEP = LocalContrast.GRID_STEP

    class Scratch(width: Int, val gain: FloatArray) { val sdr = FloatArray(width * 3); val hdr = FloatArray(width * 3) }

    /**
     * Wat de eerste pass over het (verkleinde) beeld oplevert: de piek, de kleurruimte, en een steekproef van de lineaire HDR-pixels met de
     * gemiddelde luminantie van dezelfde pixels in de SDR-versie van de camera (de basis, voor de gain map erop komt).
     */
    class Measured(
        val peak: Float, val space: String, val samples: FloatArray, val sdrSamples: FloatArray, val sampleCount: Int, val baseMean: Double,
        val gridW: Int, val gridH: Int,
    )

    /** Wat de tweede pass nodig heeft: de tone map met de gekozen belichting, het lokale contrast, en de luminantiecoëfficiënten. */
    class Prepared(val mapper: ToneMapper, val contrast: LocalContrast?, val peak: Float, val luma: FloatArray)

    fun lumaFor(space: String): FloatArray = if (space.contains("P3", ignoreCase = true)) ToneMapper.LUMA_P3 else ToneMapper.LUMA_SRGB

    /**
     * Van de eerste pass naar de tone map. [sample] is de verkleiningsfactor van het beeld waarvan de steekproef komt, [w] en [h] zijn de maten van
     * het beeld dat uiteindelijk wordt gemaakt. Lokaal contrast in de lichten komt er eerst bij (zie [LocalContrast]); de belichting wordt gekozen
     * op wat er daarna uitkomt, en op de HDR-sterkte (zie [ToneMapper.matching]).
     */
    fun prepare(first: Measured, settings: ConvertSettings, sample: Int, w: Int, h: Int): Prepared {
        var peak = first.peak
        val luma = lumaFor(first.space)
        val contrast = LocalContrast.build(first.samples, first.sampleCount, first.gridW, first.gridH, sample, w, h, luma)
        if (contrast != null) {
            // Het lokale contrast tilt de lichten op, dus de piek van wat er door de curve gaat ligt hoger dan die van de HDR zelf.
            // De verhouding komt uit de steekproef (zelfde percentiel voor en na), de piek zelf blijft die van de hele foto.
            val before = LuminanceHistogram.peakOf(first.samples, first.sampleCount, luma)
            contrast.adjustSamples(first.samples, first.sdrSamples, first.sampleCount)
            val after = LuminanceHistogram.peakOf(first.samples, first.sampleCount, luma)
            if (before > 0f && after > before) peak = (peak * after / before).coerceIn(1.0f, 4.93f)
        }
        // De belichting volgt de camera: de foto komt even helder uit als de SDR-versie die de camera zelf maakte (zie ToneMapper.matching).
        val mapper = ToneMapper.matching(settings.toneMap, peak, luma, first.samples, first.sampleCount, first.baseMean, first.sdrSamples, settings.strength)
        return Prepared(mapper, contrast, peak, luma)
    }

    /** Piek uit het 99,95e percentiel van de luminantie van de gereconstrueerde HDR, op een verkleind beeld ([raw] is ARGB_8888, [rowBytes] per rij). */
    fun analyse(
        raw: ByteArray, rowBytes: Int, bw: Int, bh: Int, space: String, meta: GainMapMeta?, gain: GainMapImage, strength: Float,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean,
    ): Measured {
        val luma = lumaFor(space)
        val sampler = GainMapSampler(meta, gain, bw, bh, strength)
        val hist = LuminanceHistogram()
        val row = FloatArray(bw * 3)
        val hdrRow = FloatArray(bw * 3)
        // Elke vierde pixel van elke vierde rij: een paar honderdduizend punten, genoeg voor een gemiddelde. Per punt de HDR en de SDR van de camera.
        val samples = FloatArray(((bw + SAMPLE_STEP - 1) / SAMPLE_STEP) * ((bh + SAMPLE_STEP - 1) / SAMPLE_STEP) * 3)
        val sdrSamples = FloatArray(samples.size)
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
            sampler.hdrRow(y, row, hdrRow)
            for (x in 0 until bw) hist.add(luma[0] * hdrRow[x * 3] + luma[1] * hdrRow[x * 3 + 1] + luma[2] * hdrRow[x * 3 + 2])
            if (sampled) for (x in 0 until bw step SAMPLE_STEP) {
                for (c in 0 until 3) { samples[sampleCount * 3 + c] = hdrRow[x * 3 + c]; sdrSamples[sampleCount * 3 + c] = row[x * 3 + c] }
                sampleCount++
            }
        }
        return Measured(
            hist.peak(), space, samples, sdrSamples, sampleCount, if (sampleCount > 0) baseSum / sampleCount else 0.0,
            (bw + SAMPLE_STEP - 1) / SAMPLE_STEP, (bh + SAMPLE_STEP - 1) / SAMPLE_STEP,
        )
    }

    /** Rijen [r0, r1) van een strook: SDR uit de bytes, HDR via de gain map, lokaal contrast, tone map (met de SDR als anker), naar 8-bit sRGB in [out]. */
    fun pixels(
        raw: ByteArray, rowBytes: Int, stripY: Int, r0: Int, r1: Int, w: Int,
        sampler: GainMapSampler, mapper: ToneMapper, contrast: LocalContrast?, s: Scratch, out: ByteArray,
    ) {
        val px = s.hdr
        val base = s.sdr
        for (r in r0 until r1) {
            var p = r * rowBytes
            for (x in 0 until w) {
                base[x * 3] = Srgb.toLinear[raw[p].toInt() and 0xFF]
                base[x * 3 + 1] = Srgb.toLinear[raw[p + 1].toInt() and 0xFF]
                base[x * 3 + 2] = Srgb.toLinear[raw[p + 2].toInt() and 0xFF]
                p += 4
            }
            sampler.hdrRow(stripY + r, base, px, s.gain)
            contrast?.adjustRow(stripY + r, px, base)
            var o = r * w * 3
            for (x in 0 until w) {
                mapper.map(px[x * 3], px[x * 3 + 1], px[x * 3 + 2], base[x * 3], base[x * 3 + 1], base[x * 3 + 2], px, x * 3)
                out[o] = Srgb.toByte(px[x * 3]).toByte()
                out[o + 1] = Srgb.toByte(px[x * 3 + 1]).toByte()
                out[o + 2] = Srgb.toByte(px[x * 3 + 2]).toByte()
                o += 3
            }
        }
    }

    /** Rijen [r0, r1) van een strook ongewijzigd van ARGB_8888 naar 8-bit RGB in [out] (HDR behouden: het hoofdbeeld verandert niet, het wordt alleen opnieuw gecodeerd). */
    fun rgb(raw: ByteArray, rowBytes: Int, r0: Int, r1: Int, w: Int, out: ByteArray) {
        for (r in r0 until r1) {
            var p = r * rowBytes
            var o = r * w * 3
            for (x in 0 until w) {
                out[o] = raw[p]; out[o + 1] = raw[p + 1]; out[o + 2] = raw[p + 2]
                p += 4; o += 3
            }
        }
    }

    /** De gain map zoals hij in het bestand staat: een complete JPEG. */
    fun gainBytes(source: JpegSource, layout: UltraHdrLayout): ByteArray {
        val bytes = ByteArray(layout.gainLength.toInt())
        source.open().use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { ins ->
                ins.channel.position(layout.gainStart)
                var got = 0
                while (got < bytes.size) { val n = ins.read(bytes, got, bytes.size - got); if (n < 0) error("gain map afgekapt"); got += n }
            }
        }
        return bytes
    }

    /** De gain map als bytes: één kanaal als alle pixels grijs zijn, anders drie. */
    fun loadGainMap(source: JpegSource, layout: UltraHdrLayout): GainMapImage {
        val bytes = gainBytes(source, layout)
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
}
