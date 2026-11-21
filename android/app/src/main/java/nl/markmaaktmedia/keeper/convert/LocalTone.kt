package nl.markmaaktmedia.keeper.convert

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * De omgeving van elke pixel voor de lokale tone map in de lichten (zie [ToneMapper.mapLocal]): `M`, het randbewuste gemiddelde van de
 * HDR-luminantie rondom de pixel. De globale curve perst de lichten bij de piek samen met een helling van zo'n 0,07, dus brede lichte vlakken
 * (lucht, zon) verloren hun tekening en liepen dicht naar wit; de camera-SDR houdt die tekening wel. Met `M` krijgt elke pixel een eigen gain
 * `c(M) * (Y / M)` en blijft het detail van de HDR staan.
 *
 * `M` is een zelfsturend filter (He et al.) op `ln Y`: vlak waar het vlak is, scherp bij randen, dus geen halo rond een silhouet tegen de zon (een
 * gewone gauss gaf daar een lichte rand). Het wordt gemeten op het steekproefraster van de eerste pass (elke vierde pixel van elke vierde rij van het
 * verkleinde beeld) en per pixel bilineair opgezocht, dus een strook van de tweede pass heeft geen buren nodig. Een port van `lt_*` in
 * `prototype/tonemap.py`.
 */
class LocalTone private constructor(
    private val gridW: Int, private val gridH: Int, val mean: FloatArray, private val sample: Int, private val width: Int,
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

    /** De piek van de omgeving (99,95e percentiel van `M`, tussen 1,0 en 4,93): daar komt de curve op de top uit (zie [ToneMapper.lt]). */
    val peak: Float = LuminanceHistogram.peakOfValues(mean, mean.size).coerceIn(1.0f, 4.93f)

    /** Rasterpositie (in cellen) van het midden van pixel [p] van het volle beeld. */
    private fun gridPos(p: Int): Float = ((p + 0.5f) / sample - 0.5f) / GRID_STEP

    /** Zet in [out] (`width` floats) het omgevingsgemiddelde van elke pixel van rij [y] van het volle beeld. Alleen lezen: veilig vanuit meer threads. */
    fun rowMeans(y: Int, out: FloatArray) {
        val pos = gridPos(y).coerceIn(0f, (gridH - 1).toFloat())
        val yl = pos.toInt()
        val yh = min(yl + 1, gridH - 1)
        val wy = pos - yl
        val rowLo = yl * gridW
        val rowHi = yh * gridW
        for (x in 0 until width) {
            val a = xLow[x]
            val b = xHigh[x]
            val wx = xWeight[x]
            val top = mean[rowLo + a] * (1f - wx) + mean[rowLo + b] * wx
            val bottom = mean[rowHi + a] * (1f - wx) + mean[rowHi + b] * wx
            out[x] = top * (1f - wy) + bottom * wy
        }
    }

    companion object {
        const val SIGMA_FRAC = 0.01f
        const val EPS = 0.1f
        const val RAD = 2f
        const val DCAP = 1.8f
        const val CHCAP = 4f
        const val M0 = 0.5f
        const val M1 = 1.2f
        const val R0 = 1.10f
        const val R1 = 1.30f
        const val HEADROOM = 0.10f
        const val SOFT = 0.85f

        /** Het raster is elke vierde pixel van elke vierde rij van het verkleinde beeld. */
        const val GRID_STEP = 4

        /**
         * [samples] zijn de lineaire HDR-pixels van het raster (r, g, b achter elkaar, `gridW * gridH` stuks, rij voor rij); [sample] is de
         * verkleiningsfactor van het beeld waarvan het raster komt; [width] en [height] zijn de maten van het volle beeld.
         * Null als het raster niet klopt (dan blijft de globale tone map).
         */
        fun build(samples: FloatArray, count: Int, gridW: Int, gridH: Int, sample: Int, width: Int, height: Int, luma: FloatArray): LocalTone? {
            if (gridW < 1 || gridH < 1 || count != gridW * gridH) return null
            val y = FloatArray(count) { luma[0] * samples[it * 3] + luma[1] * samples[it * 3 + 1] + luma[2] * samples[it * 3 + 2] }
            val sigma = SIGMA_FRAC * max(width, height) / (GRID_STEP * sample)
            return LocalTone(gridW, gridH, guided(y, gridW, gridH, sigma), sample, width)
        }

        /**
         * Het zelfsturende filter op `ln Y` (He et al., met het beeld als eigen gids): `a = var / (var + EPS)`, `b = m - a m`, uitvoer `exp(mean(a) L + mean(b))`;
         * vensters van `2 int(RAD sigma) + 1` cellen met herhaalde randen: zelfde uitkomst als het prototype (`lt_mean_grid`).
         */
        fun guided(values: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
            val half = (RAD * sigma).toInt()
            val l = FloatArray(values.size) { ln(max(values[it], 1e-4f)) }
            val m = box(l, w, h, half)
            val m2 = box(FloatArray(l.size) { l[it] * l[it] }, w, h, half)
            val a = FloatArray(l.size)
            val b = FloatArray(l.size)
            for (i in l.indices) {
                val v = m2[i] - m[i] * m[i]
                a[i] = v / (v + EPS)
                b[i] = m[i] - a[i] * m[i]
            }
            val ma = box(a, w, h, half)
            val mb = box(b, w, h, half)
            return FloatArray(l.size) { exp(ma[it] * l[it] + mb[it]) }
        }

        /** Gemiddelde over een venster van `2 half + 1` cellen, randen herhaald (scipy's `uniform_filter(mode="nearest")`). */
        fun box(values: FloatArray, w: Int, h: Int, half: Int): FloatArray {
            if (half <= 0) return values.copyOf()
            val size = 2 * half + 1
            val tmp = FloatArray(values.size)
            for (j in 0 until h) {
                val row = j * w
                var s = 0.0
                for (k in -half..half) s += values[row + k.coerceIn(0, w - 1)]
                tmp[row] = (s / size).toFloat()
                for (i in 1 until w) {
                    s += values[row + (i + half).coerceIn(0, w - 1)] - values[row + (i - half - 1).coerceIn(0, w - 1)]
                    tmp[row + i] = (s / size).toFloat()
                }
            }
            val out = FloatArray(values.size)
            for (i in 0 until w) {
                var s = 0.0
                for (k in -half..half) s += tmp[k.coerceIn(0, h - 1) * w + i]
                out[i] = (s / size).toFloat()
                for (j in 1 until h) {
                    s += tmp[(j + half).coerceIn(0, h - 1) * w + i] - tmp[(j - half - 1).coerceIn(0, h - 1) * w + i]
                    out[j * w + i] = (s / size).toFloat()
                }
            }
            return out
        }
    }
}
