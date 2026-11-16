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
}
