package nl.markmaaktmedia.keeper.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Het voorbeeld van een omzetting in de viewer: dezelfde stappen als [Converter] (via [ConvertCore]), maar op een verkleind beeld en in het
 * geheugen, zodat het bij het schuiven van de HDR-sterkte live meebeweegt. Het beeld en de gain map worden één keer gedecodeerd ([open]);
 * elke [render] rekent daarna alleen nog de gain en de (lokale) tone map opnieuw.
 */
class ConvertPreview private constructor(
    private val layout: UltraHdrLayout, private val gain: GainMapImage, private val raw: ByteArray, private val rowBytes: Int,
    val width: Int, val height: Int, private val space: String, private val colorSpace: ColorSpace?,
) {
    /** Het voorbeeld voor [settings] (de sterkte telt mee). Annuleert netjes als de coroutine wordt geannuleerd, bv. omdat de schuif verder ging. */
    suspend fun render(settings: ConvertSettings): Bitmap = withContext(Dispatchers.Default) {
        val ctx = coroutineContext
        val first = ConvertCore.analyse(raw, rowBytes, width, height, space, layout.meta, gain, settings.strength, {}) { !ctx.isActive }
        val prepared = ConvertCore.prepare(first, settings, 1, width, height)
        val sampler = GainMapSampler(layout.meta, gain, width, height, settings.strength)
        val rgb = ByteArray(width * height * 3)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val chunk = (height + workers - 1) / workers
        coroutineScope {
            (0 until workers).mapNotNull { i ->
                val r0 = i * chunk
                val r1 = minOf(r0 + chunk, height)
                if (r0 >= r1) null else async {
                    ConvertCore.pixels(raw, rowBytes, 0, r0, r1, width, sampler, prepared.mapper, prepared.tone, ConvertCore.Scratch(width, sampler.newScratch()), rgb)
                }
            }.awaitAll()
        }
        if (!coroutineContext.isActive) throw CancellationException("afgebroken")
        val pixels = IntArray(width * height)
        var p = 0
        for (i in pixels.indices) {
            pixels[i] = (0xFF shl 24) or ((rgb[p].toInt() and 0xFF) shl 16) or ((rgb[p + 1].toInt() and 0xFF) shl 8) or (rgb[p + 2].toInt() and 0xFF)
            p += 3
        }
        val bitmap = if (colorSpace != null) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, colorSpace)
            else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    }

    companion object {
        /** Zo klein mag de lange kant van het voorbeeld hooguit worden (door te verkleinen met een macht van twee, zoals de JPEG-decoder dat het snelst doet). */
        const val MIN_EDGE = 1000

        /** Null als dit geen Ultra HDR-bestand is of niet te lezen valt. Blokkeert (decoderen); roep het niet op de hoofdthread aan. */
        fun open(source: JpegSource): ConvertPreview? = try {
            val layout = UltraHdrReader.read { ParcelFileDescriptor.AutoCloseInputStream(source.open()) }
            if (layout == null) null else {
                val gain = ConvertCore.loadGainMap(source, layout)
                val long = maxOf(layout.primary.width, layout.primary.height)
                var sample = 1
                while (long / (sample * 2) >= MIN_EDGE) sample *= 2
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample }
                val fd = source.open()
                val bmp = try { BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opts) } finally { fd.close() }
                if (bmp == null) null else {
                    val space = bmp.colorSpace?.name ?: "onbekend"
                    val rowBytes = bmp.rowBytes
                    val raw = ByteArray(rowBytes * bmp.height)
                    bmp.copyPixelsToBuffer(ByteBuffer.wrap(raw))
                    val result = ConvertPreview(layout, gain, raw, rowBytes, bmp.width, bmp.height, space, bmp.colorSpace)
                    bmp.recycle()
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
