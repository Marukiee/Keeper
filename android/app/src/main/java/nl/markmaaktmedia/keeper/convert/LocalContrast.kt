package nl.markmaaktmedia.keeper.convert

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Lokaal contrast in de lichten, vóór de tone map. De curve drukt de lichten samen, waardoor ze ten opzichte van de SDR van de camera
 * zo'n 38% lokaal contrast verliezen (de foto oogt plat). Hier krijgt de HDR dat terug: `Y' = Y * (Y / M)^(alfa * w * g)`, met
 *
 * - `M` het wazige gemiddelde van de HDR-luminantie in de omgeving (gauss, sigma [SIGMA_FRAC] van de lange kant);
 * - `w` 0 onder een omgeving van [M0] en 1 boven [M1]: alleen lichte omgevingen, schaduwen en mids blijven zoals ze zijn;
 * - `g` 0 bij een opticht van de gain map onder [R0] en 1 boven [R1]: de witte omlijsting van een Hasselblad-foto en haar tekst hebben geen
 *   gain en vallen dus buiten; dat houdt het kader op 255 (zie [ToneMapper.anchor]).
 *
 * Het gemiddelde komt van het steekproefraster van de eerste pass (elke vierde pixel van elke vierde rij van het verkleinde beeld) en
 * wordt per pixel bilineair opgezocht, dus een strook van de tweede pass heeft geen buren nodig. Een port van `lc_*` in `prototype/tonemap.py`.
 */
class LocalContrast private constructor(
    private val gridW: Int, private val gridH: Int, private val mean: FloatArray, private val sample: Int,
    private val width: Int, private val luma: FloatArray,
) {
    // Per kolom van het volle beeld de twee rasterkolommen en het gewicht, eenmalig (zoals GainMapSampler).
    private val xLow = IntArray(width)
    private val xHigh = IntArray(width)
    private val xWeight = FloatArray(width)

    init {
        for (x in 0 until width) {
            val pos = gridPos(x).coerceIn(0f, (gridW - 1).toFloat())
            val lo = pos.toInt()
            xLow[x] = lo
            xHigh[x] = min(lo + 1, gridW - 1)
            xWeight[x] = pos - lo
        }
    }

    /** Rasterpositie (in cellen) van het midden van pixel [p] van het volle beeld. */
    private fun gridPos(p: Int): Float = ((p + 0.5f) / sample - 0.5f) / GRID_STEP

    /**
     * Past rij [y] van het volle beeld aan: [hdr] (lineaire HDR, `width * 3` floats) wordt in place met de vermenigvuldiger geschaald.
     * [sdr] is dezelfde rij in de SDR van de camera. Veilig om vanuit meer threads tegelijk aan te roepen (alleen lezen).
     */
    fun adjustRow(y: Int, hdr: FloatArray, sdr: FloatArray) {
        val pos = gridPos(y).coerceIn(0f, (gridH - 1).toFloat())
        val yl = pos.toInt()
        val yh = min(yl + 1, gridH - 1)
        val wy = pos - yl
        val rowLo = yl * gridW
        val rowHi = yh * gridW
        for (x in 0 until width) {
            val i = x * 3
            val lh = luma[0] * hdr[i] + luma[1] * hdr[i + 1] + luma[2] * hdr[i + 2]
            val ls = luma[0] * sdr[i] + luma[1] * sdr[i + 1] + luma[2] * sdr[i + 2]
            // Goedkoop eerst: wie niet merkbaar opgetild is, krijgt niets.
            if (lh <= ls * R0) continue
            val a = xLow[x]
            val b = xHigh[x]
            val wx = xWeight[x]
            val top = mean[rowLo + a] * (1f - wx) + mean[rowLo + b] * wx
            val bottom = mean[rowHi + a] * (1f - wx) + mean[rowHi + b] * wx
            val f = factor(lh, ls, top * (1f - wy) + bottom * wy)
            if (f != 1f) { hdr[i] *= f; hdr[i + 1] *= f; hdr[i + 2] *= f }
        }
    }

    /** Dezelfde aanpassing op de steekproef van de eerste pass (zodat de belichting op wat er uiteindelijk uitkomt wordt gekozen). */
    fun adjustSamples(samples: FloatArray, sdrSamples: FloatArray, count: Int) {
        for (n in 0 until min(count, mean.size)) {
            val i = n * 3
            val lh = luma[0] * samples[i] + luma[1] * samples[i + 1] + luma[2] * samples[i + 2]
            val ls = luma[0] * sdrSamples[i] + luma[1] * sdrSamples[i + 1] + luma[2] * sdrSamples[i + 2]
            val f = factor(lh, ls, mean[n])
            if (f != 1f) { samples[i] *= f; samples[i + 1] *= f; samples[i + 2] *= f }
        }
    }

    companion object {
        const val ALPHA = 0.6f
        const val SIGMA_FRAC = 0.005f
        const val M0 = 0.5f
        const val M1 = 1.2f
        const val R0 = 1.10f
        const val R1 = 1.30f
        const val SMAX = 1.8f

        /** Het raster is elke vierde pixel van elke vierde rij van het verkleinde beeld. */
        const val GRID_STEP = 4

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Vermenigvuldiger voor een HDR-pixel met luminantie [yh], SDR-luminantie [ys] en omgevingsgemiddelde [m] (alles lineair). */
        fun factor(yh: Float, ys: Float, m: Float): Float {
            val g = smooth(R0, R1, yh / max(ys, 1e-4f))
            if (g <= 0f) return 1f
            val w = smooth(M0, M1, m)
            if (w <= 0f) return 1f
            val e = ALPHA * w * g
            return exp(e * ln(max(yh, 1e-4f) / max(m, 1e-4f))).coerceIn(1f / SMAX, SMAX)
        }

        /**
         * [samples] zijn de lineaire HDR-pixels van het raster (r, g, b achter elkaar, `gridW * gridH` stuks, rij voor rij); [sample] is de
         * verkleiningsfactor van het beeld waarvan het raster komt; [width] en [height] zijn de maten van het volle beeld.
         * Null als het raster niet klopt (dan blijft de foto zoals hij was).
         */
        fun build(samples: FloatArray, count: Int, gridW: Int, gridH: Int, sample: Int, width: Int, height: Int, luma: FloatArray): LocalContrast? {
            if (gridW < 1 || gridH < 1 || count != gridW * gridH) return null
            val y = FloatArray(count) { luma[0] * samples[it * 3] + luma[1] * samples[it * 3 + 1] + luma[2] * samples[it * 3 + 2] }
            val sigma = SIGMA_FRAC * max(width, height) / (GRID_STEP * sample)
            return LocalContrast(gridW, gridH, blur(y, gridW, gridH, sigma), sample, width, luma)
        }

        /** Gauss met straal `int(3 sigma + 0,5)` en herhaalde randen: zelfde uitkomst als scipy's `gaussian_filter(mode="nearest", truncate=3)`. */
        fun blur(values: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
            if (sigma < 0.05f) return values.copyOf()
            val radius = (3f * sigma + 0.5f).toInt()
            val kernel = FloatArray(2 * radius + 1) { exp(-0.5f * (it - radius) * (it - radius) / (sigma * sigma)) }
            val total = kernel.sum()
            for (i in kernel.indices) kernel[i] /= total
            val tmp = FloatArray(values.size)
            for (j in 0 until h) for (i in 0 until w) {
                var s = 0f
                for (k in -radius..radius) s += kernel[k + radius] * values[j * w + (i + k).coerceIn(0, w - 1)]
                tmp[j * w + i] = s
            }
            val out = FloatArray(values.size)
            for (j in 0 until h) for (i in 0 until w) {
                var s = 0f
                for (k in -radius..radius) s += kernel[k + radius] * tmp[(j + k).coerceIn(0, h - 1) * w + i]
                out[j * w + i] = s
            }
            return out
        }
    }
}
