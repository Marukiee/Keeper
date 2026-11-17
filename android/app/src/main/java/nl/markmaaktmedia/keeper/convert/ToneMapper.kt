package nl.markmaaktmedia.keeper.convert

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Globale tone map van lineaire HDR naar SDR, per pixel (dus geschikt voor rijstroken). Een port van `prototype/tonemap.py`;
 * de paritytest vergelijkt de uitkomst met dat prototype.
 *
 * Luminantie krijgt een curve met een zachte schouder boven de knie, de kleurverhouding blijft, wat daarna nog boven 1,0
 * uitkomt wordt naar grijs gedesatureerd. Een halve luminantiecurve en een halve per-kanaalcurve (mix 0,5) houdt blauwe lucht blauw.
 */
class ToneMapper(
    peak: Float,
    val exposure: Float,
    val knee: Float,
    private val mix: Float = 0.5f,
    private val luma: FloatArray = LUMA_P3,
) {
    private val invKnee = 1f / (1f - knee)
    private val w2: Float = max((peak * exposure - knee) * invKnee, 1e-3f).let { it * it }

    // Tabel van de curve tussen 0 en de piek (daarboven is de uitkomst 1,0). De curve is overal glad (ook bij de knie), dus
    // lineair interpoleren tussen 4096 punten geeft een fout van orde 1e-7 en bespaart twee delingen per aanroep.
    private val top = max(peak, knee / exposure + 1e-3f)
    private val lutScale = LUT_SIZE / top
    private val lut = FloatArray(LUT_SIZE + 2) { curve(it / lutScale) }

    private fun fast(v: Float): Float {
        if (v >= top) return 1f
        if (v <= 0f) return 0f
        val f = v * lutScale
        val i = f.toInt()
        val a = lut[i]
        return a + (lut[i + 1] - a) * (f - i)
    }

    /** Lineair tot de knie, daarboven een zachte rolloff die de piek precies op wit brengt. */
    fun curve(v: Float): Float {
        val vs = v * exposure
        if (vs <= knee) return min(vs, 1f)
        val x = (vs - knee) * invKnee
        val g = x * (1f + x / w2) / (1f + x)
        return min(knee + (1f - knee) * g, 1f)
    }

    /** Schrijft lineaire SDR in [0,1] naar `out[o..o+2]`, zelfde gamut als de invoer. */
    fun map(r: Float, g: Float, b: Float, out: FloatArray, o: Int) {
        val y = luma[0] * r + luma[1] * g + luma[2] * b
        val yo = fast(y)
        val scale = if (y > 1e-6f) yo / y else exposure
        var or = r * scale
        var og = g * scale
        var ob = b * scale
        val m = max(or, max(og, ob))
        if (m > 1f) {
            val t = (1f - yo) / max(m - yo, 1e-6f)
            or = yo + (or - yo) * t
            og = yo + (og - yo) * t
            ob = yo + (ob - yo) * t
        }
        if (mix < 1f) {
            or = mix * or + (1f - mix) * fast(r)
            og = mix * og + (1f - mix) * fast(g)
            ob = mix * ob + (1f - mix) * fast(b)
        }
        out[o] = or.coerceIn(0f, 1f)
        out[o + 1] = og.coerceIn(0f, 1f)
        out[o + 2] = ob.coerceIn(0f, 1f)
    }

    /**
     * Zoals [map], maar een lichte pixel die de camera niet heeft opgetild blijft zoals de camera-SDR hem gaf ([sr], [sg], [sb] zijn die
     * SDR, lineair en in hetzelfde gamut). Papierwit zonder gain, zoals de witte rand van een Hasselblad-omlijsting, heeft in de HDR-weergave
     * precies de helderheid van het scherm-wit; de schouder zou hem anders onder wit zetten (220 in plaats van 255) om ruimte te maken voor
     * lichten die er in deze pixel niet zijn.
     */
    fun map(r: Float, g: Float, b: Float, sr: Float, sg: Float, sb: Float, out: FloatArray, o: Int) {
        val ys = luma[0] * sr + luma[1] * sg + luma[2] * sb
        // Eerst het goedkope pad: de meeste pixels zijn donkerder dan de ondergrens van het anker.
        val a = if (ys <= ANCHOR_Y0) 0f else anchor(luma[0] * r + luma[1] * g + luma[2] * b, ys)
        map(r, g, b, out, o)
        if (a > 0f) {
            out[o] += (sr - out[o]) * a
            out[o + 1] += (sg - out[o + 1]) * a
            out[o + 2] += (sb - out[o + 2]) * a
        }
    }

    companion object {
        private const val LUT_SIZE = 4096

        // Anker: volledig bij een opticht van minder dan 5% (0,07 stop) in de luminantie, weg bij 30% (0,38 stop); en pas bij lichte
        // pixels (SDR-luminantie 0,6 tot 0,95, geleidelijk), zodat donkere vlakken zonder gain gewoon de curve volgen.
        private const val ANCHOR_R0 = 1.05f
        private const val ANCHOR_R1 = 1.30f
        private const val ANCHOR_Y0 = 0.60f
        private const val ANCHOR_Y1 = 0.95f

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Het gewicht in [0, 1] waarmee een pixel bij de camera-SDR blijft: [hdrLuma] en [sdrLuma] zijn lineaire luminanties. */
        fun anchor(hdrLuma: Float, sdrLuma: Float): Float {
            if (sdrLuma <= ANCHOR_Y0) return 0f
            val ratio = hdrLuma / sdrLuma
            if (ratio >= ANCHOR_R1) return 0f
            return (1f - smooth(ANCHOR_R0, ANCHOR_R1, ratio)) * smooth(ANCHOR_Y0, ANCHOR_Y1, sdrLuma)
        }

        /** Luminantiecoefficienten voor lineair Display P3 en voor sRGB (Rec. 709). */
        val LUMA_P3 = floatArrayOf(0.2289746f, 0.6917385f, 0.0792869f)
        val LUMA_SRGB = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        /**
         * De knie van de uitvoer ligt een kwart van de resterende ruimte tot wit hoger dan die van de sterkte (Normaal 0,45 wordt 0,59): wat onder de
         * knie zit blijft lineair, dus een hogere knie maakt de lichten helderder zonder dat schaduwen en mids bewegen. De belichting wordt wel op de
         * knie van de sterkte zelf gekozen (zie [matching]); de foto komt daardoor gemiddeld zo'n 3 tot 4% lichter uit dan de camera-SDR, alleen in
         * de lichten. Zie `lifted_knee` in `prototype/tonemap.py`.
         */
        const val KNEE_LIFT = 0.25f

        fun liftedKnee(knee: Float) = knee + KNEE_LIFT * (1f - knee)

        /** Mix is overal 0,5; belichting en knie per sterkte staan in [nl.markmaaktmedia.keeper.scan.ToneMap]. */
        fun of(t: nl.markmaaktmedia.keeper.scan.ToneMap, peak: Float, luma: FloatArray) =
            ToneMapper(peak, t.exposure.toFloat(), liftedKnee(t.knee.toFloat()), 0.5f, luma)

        /** Zo donker mag de belichting hooguit worden gezet om de foto op de helderheid van de camera te brengen. */
        const val MIN_EXPOSURE = 0.5f

        /**
         * Dezelfde tone map, maar met de belichting zo gekozen dat de gemiddelde helderheid van het resultaat gelijk is aan die van de
         * SDR-versie die de camera zelf maakte ([targetMean], lineaire luminantie). Zonder dit komt de HDR-weergave (de gain map tilt de
         * midden- en schaduwtonen op) gemiddeld zo'n 30% lichter uit dan de camera-SDR, met opgetilde zwarten en bleke huid. De belichting
         * van de sterkte is het maximum: een foto die al donkerder is dan de camera-SDR wordt niet verder opgehelderd. De belichting wordt op
         * de knie van de sterkte gekozen; de teruggegeven mapper heeft die knie [liftedKnee] hoger (lichten helderder, de rest gelijk).
         *
         * [samples] zijn lineaire HDR-pixels (r, g, b achter elkaar, [count] stuks) uit de eerste pass. [sdrSamples] zijn dezelfde pixels
         * in de SDR van de camera; met die erbij telt het anker (zie [map] met SDR) mee in het gemiddelde, zoals het in de uitvoer meetelt.
         */
        fun matching(
            t: nl.markmaaktmedia.keeper.scan.ToneMap, peak: Float, luma: FloatArray,
            samples: FloatArray, count: Int, targetMean: Double, sdrSamples: FloatArray? = null,
        ): ToneMapper {
            val cap = t.exposure.toFloat()
            if (count == 0 || cap <= MIN_EXPOSURE) return of(t, peak, luma)
            val out = FloatArray(3)
            fun meanAt(exposure: Float): Double {
                val m = ToneMapper(peak, exposure, t.knee.toFloat(), 0.5f, luma)
                var sum = 0.0
                for (i in 0 until count) {
                    if (sdrSamples != null) m.map(samples[i * 3], samples[i * 3 + 1], samples[i * 3 + 2], sdrSamples[i * 3], sdrSamples[i * 3 + 1], sdrSamples[i * 3 + 2], out, 0)
                    else m.map(samples[i * 3], samples[i * 3 + 1], samples[i * 3 + 2], out, 0)
                    sum += luma[0] * out[0] + luma[1] * out[1] + luma[2] * out[2]
                }
                return sum / count
            }
            // Het resultaat wordt lichter met de belichting, dus bisectie op de grootste belichting die niet boven het doel uitkomt.
            var lo = MIN_EXPOSURE
            var hi = cap
            if (meanAt(hi) <= targetMean) return ToneMapper(peak, hi, liftedKnee(t.knee.toFloat()), 0.5f, luma)
            repeat(14) {
                val mid = (lo + hi) / 2
                if (meanAt(mid) > targetMean) hi = mid else lo = mid
            }
            return ToneMapper(peak, lo, liftedKnee(t.knee.toFloat()), 0.5f, luma)
        }
    }
}

/**
 * Luminantiehistogram van de eerste pass: de piek van de inhoud is het 99,95e percentiel, tussen 1,0 en 4,93.
 * Dezelfde bakken als `peak_from_hist` in het prototype: [0, 1e-4) en daarna 2000 logaritmische bakken tot 8.
 */
class LuminanceHistogram {
    private val edges = DoubleArray(BINS + 1).also {
        it[0] = 0.0
        val lo = ln(1e-4)
        val hi = ln(8.0)
        for (i in 0 until BINS) it[i + 1] = exp(lo + (hi - lo) * i / (BINS - 1))
        it[1] = 1e-4
        it[BINS] = 8.0
    }
    private val counts = LongArray(BINS)

    fun add(y: Float) {
        val v = y.toDouble()
        if (v < 0.0 || v > 8.0) return
        // Rechtse bovengrens, zoals np.histogram: de laatste bak sluit de bovenrand in.
        var lo = 0
        var hi = BINS
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (edges[mid + 1] <= v) lo = mid + 1 else hi = mid
        }
        counts[min(lo, BINS - 1)]++
    }

    fun peak(percent: Double = 99.95, lo: Float = 1.0f, hi: Float = 4.93f): Float {
        val total = max(counts.sum(), 1L).toDouble()
        var run = 0L
        var idx = BINS
        for (i in 0 until BINS) {
            run += counts[i]
            if (run / total >= percent / 100.0) { idx = i; break }
        }
        return edges[min(idx, BINS - 1) + 1].toFloat().coerceIn(lo, hi)
    }

    companion object {
        private const val BINS = 2000

        /** Het 99,95e percentiel van de luminantie van een steekproef (r, g, b achter elkaar), zonder de grenzen van [peak]; 0 als er niets is. */
        fun peakOf(samples: FloatArray, count: Int, luma: FloatArray): Float {
            if (count == 0) return 0f
            val h = LuminanceHistogram()
            for (i in 0 until count) h.add(luma[0] * samples[i * 3] + luma[1] * samples[i * 3 + 1] + luma[2] * samples[i * 3 + 2])
            return h.peak(lo = 1e-3f, hi = 8f)
        }
    }
}
