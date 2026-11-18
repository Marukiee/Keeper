package nl.markmaaktmedia.keeper.convert

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.pow

/**
 * Het voorbeeld van HDR behouden in de viewer: de foto met zijn gain map, verkleind (de lange kant tussen [ConvertPreview.MIN_EDGE] en het dubbele),
 * éénmaal gedecodeerd door Android zelf ([ImageDecoder]). [render] geeft per HDR-sterkte een nieuwe bitmap met dezelfde gain map, geschaald zoals
 * [GainMapMeta.scaled] het in het bestand zet: de ratio's tot de macht sterkte, de HDR-offset (epsilon) schuift naar de SDR-offset. Dat is vrijwel
 * gratis (de pixels worden gekopieerd, de gain map gedeeld), dus de schuif beweegt zonder wachten mee.
 *
 * Een scherm laat er pas HDR van zien als het venster in HDR-modus staat (`Window.colorMode`); dat zet de viewer alleen aan zolang dit voorbeeld
 * zichtbaar is.
 */
@RequiresApi(34)
class HdrPreview private constructor(private val base: Bitmap, private val source: Gainmap) {
    val width: Int get() = base.width
    val height: Int get() = base.height

    /** De foto met de gain map op [strength] (0 tot 1): 1 is de HDR van de camera, 0 de gewone SDR. Een nieuwe bitmap per aanroep, zodat het scherm hem opnieuw tekent. */
    fun render(strength: Float): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        val out = base.copy(Bitmap.Config.ARGB_8888, false) ?: error("voorbeeld niet te kopiëren")
        out.gainmap = scaled(source, s)
        return out
    }

    companion object {
        /** Null als dit geen foto met gain map is of Android hem niet kan lezen. Blokkeert (decoderen); roep het niet op de hoofdthread aan. */
        fun open(resolver: ContentResolver, uri: Uri): HdrPreview? = try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (long / (sample * 2) >= ConvertPreview.MIN_EDGE) sample *= 2
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
            bitmap.gainmap?.let { HdrPreview(bitmap, it) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        /** Een kopie van [g] (de beelddata wordt gedeeld) met de sterkte [s] erin. */
        internal fun scaled(g: Gainmap, s: Float): Gainmap {
            val out = Gainmap(g.gainmapContents)
            val min = g.ratioMin
            val max = g.ratioMax
            out.setRatioMin(min[0].pow(s), min[1].pow(s), min[2].pow(s))
            out.setRatioMax(max[0].pow(s), max[1].pow(s), max[2].pow(s))
            val gamma = g.gamma
            out.setGamma(gamma[0], gamma[1], gamma[2])
            val sdr = g.epsilonSdr
            val hdr = g.epsilonHdr
            out.setEpsilonSdr(sdr[0], sdr[1], sdr[2])
            // Dezelfde schuif als de HDR-offset in het bestand: bij gelijke offsets (de camera's) verandert er niets.
            out.setEpsilonHdr(sdr[0] + s * (hdr[0] - sdr[0]), sdr[1] + s * (hdr[1] - sdr[1]), sdr[2] + s * (hdr[2] - sdr[2]))
            out.displayRatioForFullHdr = g.displayRatioForFullHdr
            out.minDisplayRatioForHdrTransition = g.minDisplayRatioForHdrTransition
            return out
        }
    }
}

/** Of dit toestel gain maps kan tonen (Android 14 en nieuwer); [HdrPreview] is alleen daarboven te gebruiken. */
val hdrPreviewSupported: Boolean get() = Build.VERSION.SDK_INT >= 34
