package nl.markmaaktmedia.keeper.convert

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verwachte waarden komen uit `prototype/tonemap.py` (zie de commit die deze test toevoegt). */
class ToneMapperTest {
    private val inputs = arrayOf(
        floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0f, 0f, 0f), floatArrayOf(2.0f, 1.0f, 0.5f), floatArrayOf(4.9f, 4.9f, 4.9f),
        floatArrayOf(0.2f, 0.6f, 1.5f), floatArrayOf(1.2f, 0.1f, 0.1f), floatArrayOf(0.9f, 0.4f, 0.3f), floatArrayOf(0.05f, 0.02f, 0.03f),
    )

    private fun check(m: ToneMapper, expected: Array<FloatArray>) {
        val out = FloatArray(3)
        inputs.forEachIndexed { i, p ->
            m.map(p[0], p[1], p[2], out, 0)
            for (c in 0..2) assertEquals("pixel $i kanaal $c", expected[i][c], out[c], 1e-4f)
        }
    }

    @Test fun normaalPeak2() = check(
        ToneMapper(2.0f, 0.90f, 0.45f),
        arrayOf(
            floatArrayOf(0.45f, 0.45f, 0.45f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1.0f, 0.73981f, 0.536382f), floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.247337f, 0.527494f, 0.94205f), floatArrayOf(0.899736f, 0.101879f, 0.101879f),
            floatArrayOf(0.750564f, 0.359979f, 0.269985f), floatArrayOf(0.045f, 0.018f, 0.027f),
        ),
    )

    @Test fun lichtPeak2() = check(
        ToneMapper(2.0f, 1.00f, 0.60f),
        arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1.0f, 0.82609f, 0.626891f), floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.303172f, 0.594504f, 0.963893f), floatArrayOf(0.934694f, 0.129697f, 0.129697f),
            floatArrayOf(0.840962f, 0.4f, 0.3f), floatArrayOf(0.05f, 0.02f, 0.03f),
        ),
    )

    @Test fun lichtenBehoudenPeak2() = check(
        ToneMapper(2.0f, 0.80f, 0.40f),
        arrayOf(
            floatArrayOf(0.4f, 0.4f, 0.4f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1.0f, 0.687892f, 0.471839f), floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.198019f, 0.472022f, 0.928571f), floatArrayOf(0.858621f, 0.08f, 0.08f),
            floatArrayOf(0.678231f, 0.319987f, 0.23999f), floatArrayOf(0.04f, 0.016f, 0.024f),
        ),
    )

    @Test fun normaalPeak493() = check(
        ToneMapper(4.93f, 0.90f, 0.45f),
        arrayOf(
            floatArrayOf(0.45f, 0.45f, 0.45f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0.929522f, 0.695126f, 0.491673f),
            floatArrayOf(0.999095f, 0.999095f, 0.999095f), floatArrayOf(0.246477f, 0.525969f, 0.901005f),
            floatArrayOf(0.875022f, 0.101879f, 0.101879f), floatArrayOf(0.740092f, 0.359976f, 0.269982f), floatArrayOf(0.045f, 0.018f, 0.027f),
        ),
    )

    @Test fun peakFromHistogramMatchesPrototype() {
        val h = LuminanceHistogram()
        for (i in 0 until 6000) h.add(i * 0.001f)
        assertEquals(4.93f, h.peak(), 1e-6f) // zou boven de bovengrens uitkomen en wordt afgekapt

        val h2 = LuminanceHistogram()
        repeat(900) { h2.add(0.3f) }
        repeat(100) { h2.add(1.7f) }
        assertEquals(1.7022785592256717f, h2.peak(), 2e-4f)

        val h3 = LuminanceHistogram()
        repeat(500) { h3.add(0.4f) }
        assertEquals(1.0f, h3.peak(), 1e-6f) // ondergrens
    }

    // ---- belichting die de camera volgt ----

    private fun grayRamp(n: Int, scale: Float) = FloatArray(n * 3).also { a ->
        for (i in 0 until n) { val v = scale * (0.02f + 1.6f * i / (n - 1)); a[i * 3] = v; a[i * 3 + 1] = v; a[i * 3 + 2] = v }
    }

    private fun meanOf(m: ToneMapper, samples: FloatArray, luma: FloatArray): Double {
        val out = FloatArray(3)
        var sum = 0.0
        for (i in 0 until samples.size / 3) { m.map(samples[i * 3], samples[i * 3 + 1], samples[i * 3 + 2], out, 0); sum += luma[0] * out[0] + luma[1] * out[1] + luma[2] * out[2] }
        return sum / (samples.size / 3)
    }

    @Test fun `de belichting wordt zo gezet dat het gemiddelde het doel haalt`() {
        val luma = ToneMapper.LUMA_P3
        val samples = grayRamp(2000, 1.0f)
        // Het doel ligt onder wat de vaste belichting 0,90 geeft: de belichting moet omlaag.
        val fixed = meanOf(ToneMapper.of(nl.markmaaktmedia.keeper.scan.ToneMap.Normaal, 2.0f, luma), samples, luma)
        val target = fixed * 0.8
        val m = ToneMapper.matching(nl.markmaaktmedia.keeper.scan.ToneMap.Normaal, 2.0f, luma, samples, 2000, target)
        assertEquals(target, meanOf(m, samples, luma), target * 0.005)
    }

    @Test fun `een foto die al donkerder is dan de camera wordt niet verder opgehelderd`() {
        val luma = ToneMapper.LUMA_P3
        val samples = grayRamp(500, 0.5f)
        val m = ToneMapper.matching(nl.markmaaktmedia.keeper.scan.ToneMap.Normaal, 2.0f, luma, samples, 500, 10.0)
        check(m, arrayOf(
            floatArrayOf(0.45f, 0.45f, 0.45f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1.0f, 0.73981f, 0.536382f), floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.247337f, 0.527494f, 0.94205f), floatArrayOf(0.899736f, 0.101879f, 0.101879f),
            floatArrayOf(0.750564f, 0.359979f, 0.269985f), floatArrayOf(0.045f, 0.018f, 0.027f),
        ))
    }

    @Test fun `de belichting zakt nooit onder de ondergrens`() {
        val luma = ToneMapper.LUMA_P3
        val samples = grayRamp(500, 1.0f)
        val m = ToneMapper.matching(nl.markmaaktmedia.keeper.scan.ToneMap.Normaal, 2.0f, luma, samples, 500, 0.0001)
        val out = FloatArray(3)
        m.map(0.2f, 0.2f, 0.2f, out, 0)
        assertEquals(0.2f * ToneMapper.MIN_EXPOSURE, out[0], 1e-4f)
    }

    // ---- Anker: lichte pixels zonder opticht blijven zoals de camera-SDR (waarden uit prototype/tonemap.py, tonemap_anchored met peak 2, Normaal) ----

    private fun anchored(h: FloatArray, s: FloatArray): FloatArray {
        val out = FloatArray(3)
        ToneMapper(2.0f, 0.90f, 0.45f).map(h[0], h[1], h[2], s[0], s[1], s[2], out, 0)
        return out
    }

    private fun assertRgb(expected: FloatArray, actual: FloatArray) { for (c in 0..2) assertEquals("kanaal $c", expected[c], actual[c], 1e-4f) }

    @Test fun `de witte rand zonder gain blijft wit`() =
        assertRgb(floatArrayOf(1f, 1f, 1f), anchored(floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f)))

    @Test fun `zonder anker zakt dezelfde pixel onder wit`() {
        val out = FloatArray(3)
        ToneMapper(2.0f, 0.90f, 0.45f).map(1f, 1f, 1f, out, 0)
        assertEquals(0.73111105f, out[0], 1e-4f)
    }

    @Test fun `een opgetilde pixel volgt de curve`() =
        assertRgb(floatArrayOf(1f, 1f, 1f), anchored(floatArrayOf(2f, 2f, 2f), floatArrayOf(1f, 1f, 1f)))

    @Test fun `een donkere pixel zonder gain volgt de curve`() =
        assertRgb(floatArrayOf(0.36f, 0.36f, 0.36f), anchored(floatArrayOf(0.4f, 0.4f, 0.4f), floatArrayOf(0.4f, 0.4f, 0.4f)))

    @Test fun `de overgang naar het anker is geleidelijk en kleurt mee`() {
        assertRgb(floatArrayOf(0.965624f, 0.8714f, 0.195503f), anchored(floatArrayOf(1.0f, 0.9f, 0.2f), floatArrayOf(1.0f, 0.9f, 0.2f)))
        assertRgb(floatArrayOf(0.739044f, 0.739044f, 0.739044f), anchored(floatArrayOf(0.92f, 0.92f, 0.92f), floatArrayOf(0.8f, 0.8f, 0.8f)))
        assertRgb(floatArrayOf(0.705267f, 0.705267f, 0.705267f), anchored(floatArrayOf(0.8f, 0.8f, 0.8f), floatArrayOf(0.77f, 0.77f, 0.77f)))
    }

    @Test fun `het anker springt nergens`() {
        // Over een opticht van 1,0 tot 1,5 en een SDR van 0,5 tot 1,0 verandert het gewicht per stap van 0,005 hooguit zoals een gladde curve
        // (steilste helling van de smoothstep op de opticht: 6 per eenheid, dus 0,03 per stap; op de SDR 4,3, dus 0,021).
        for (y in 0..100) for (r in 0..100) {
            val ys = 0.5f + y * 0.005f
            val ratio = 1.0f + r * 0.005f
            val a = ToneMapper.anchor(ys * ratio, ys)
            if (r < 100) assertEquals("ys=$ys ratio=$ratio", a, ToneMapper.anchor(ys * (ratio + 0.005f), ys), 0.031f)
            if (y < 100) assertEquals("ys=$ys ratio=$ratio", a, ToneMapper.anchor((ys + 0.005f) * ratio, ys + 0.005f), 0.03f)
        }
    }

    @Test fun `het anker telt mee als de belichting wordt gekozen`() {
        val luma = ToneMapper.LUMA_P3
        // Een derde papierwit, de rest een lichte opgetilde vlak. Zonder anker ziet de bisectie de rand te donker en laat de belichting hoog;
        // met anker is de rand gelijk aan de camera en moet de rest donkerder om op hetzelfde gemiddelde te komen.
        val n = 600
        val hdr = FloatArray(n * 3); val sdr = FloatArray(n * 3)
        for (i in 0 until n) {
            val v = if (i < 200) 1.0f else 1.1f // rand: HDR = SDR = 1.0; rest: SDR 0.55 opgetild naar 1,1 (factor 2)
            val base = if (i < 200) 1.0f else 0.55f
            for (c in 0..2) { hdr[i * 3 + c] = v; sdr[i * 3 + c] = base }
        }
        val target = (200 * 1.0 + 400 * 0.55) / 600
        val without = ToneMapper.matching(nl.markmaaktmedia.keeper.scan.ToneMap.Normaal, 2.0f, luma, hdr, n, target)
        val with = ToneMapper.matching(nl.markmaaktmedia.keeper.scan.ToneMap.Normaal, 2.0f, luma, hdr, n, target, sdr)
        val a = FloatArray(3); val b = FloatArray(3)
        without.map(0.6f, 0.6f, 0.6f, a, 0)
        with.map(0.6f, 0.6f, 0.6f, b, 0)
        assert(b[0] <= a[0]) { "met anker ${b[0]} zonder ${a[0]}" }
        val out = FloatArray(3)
        var sum = 0.0
        for (i in 0 until n) { with.map(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2], sdr[i * 3], sdr[i * 3 + 1], sdr[i * 3 + 2], out, 0); sum += luma[0] * out[0] + luma[1] * out[1] + luma[2] * out[2] }
        assert(sum / n <= target * 1.005) { "gemiddelde ${sum / n} doel $target" }
    }
}
