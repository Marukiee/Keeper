package nl.markmaaktmedia.keeper.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verwachte waarden komen uit `prototype/tonemap.py` (`lt_mean_grid`, `lt_curve`, `lt_map`, `lt_top`, `lc_lookup`). */
class LocalToneTest {
    private val luma = ToneMapper.LUMA_P3

    // Raster van 9 x 7, grijs: 0,3 + 0,35 * ((i * 7 + j * 3) % 11)
    private fun grid(): FloatArray {
        val s = FloatArray(9 * 7 * 3)
        for (j in 0 until 7) for (i in 0 until 9) {
            val v = 0.3f + 0.35f * ((i * 7 + j * 3) % 11)
            for (c in 0..2) s[(j * 9 + i) * 3 + c] = v
        }
        return s
    }

    private fun gridLuma() = FloatArray(63) { 0.3f + 0.35f * (((it % 9) * 7 + (it / 9) * 3) % 11) }

    @Test fun `het zelfsturende filter geeft hetzelfde als het prototype`() {
        val m = LocalTone.guided(gridLuma(), 9, 7, 1.5f)
        val row0 = floatArrayOf(0.355481f, 2.543316f, 1.373464f, 3.353138f, 2.259289f, 1.079480f, 3.039726f, 1.961096f, 0.747417f)
        for (i in 0 until 9) assertEquals("rij 0 kolom $i", row0[i], m[i], 3e-4f)
        val row3 = floatArrayOf(3.083744f, 1.984465f, 0.754382f, 2.814170f, 1.701394f, 0.403173f, 2.538975f, 1.412298f, 3.303758f)
        for (i in 0 until 9) assertEquals("rij 3 kolom $i", row3[i], m[3 * 9 + i], 3e-4f)
        val row6 = floatArrayOf(2.557524f, 1.428081f, 3.329099f, 2.282601f, 1.113048f, 3.080799f, 2.018188f, 0.798711f, 2.835577f)
        for (i in 0 until 9) assertEquals("rij 6 kolom $i", row6[i], m[6 * 9 + i], 3e-4f)
    }

    @Test fun `een venster van een cel laat het raster zoals het is`() {
        val y = gridLuma()
        val m = LocalTone.guided(y, 9, 7, 0.3f)
        for (i in y.indices) assertEquals(y[i], m[i], 1e-5f)
    }

    @Test fun `het filter volgt een rand en maakt een vlak vlak`() {
        // Links 0,2, rechts 4,0: bij de rand blijft het gemiddelde bij de eigen kant (geen halo), ver van de rand is het vlak.
        val w = 40; val h = 6
        val y = FloatArray(w * h) { if (it % w < 20) 0.2f else 4.0f }
        val m = LocalTone.guided(y, w, h, 3f)
        assertTrue("donkere kant van de rand: ${m[2 * w + 19]}", m[2 * w + 19] < 0.25f)
        assertTrue("lichte kant van de rand: ${m[2 * w + 20]}", m[2 * w + 20] > 3.5f)
        assertEquals(0.2f, m[2 * w + 3], 1e-3f)
        assertEquals(4.0f, m[2 * w + 36], 1e-3f)
    }

    @Test fun `het omgevingsgemiddelde wordt bilineair opgezocht`() {
        // Breedte 1200 en verkleiningsfactor 1: sigma = 0,01 * 1200 / 4 = 3 cellen, venster van 13.
        val t = LocalTone.build(grid(), 63, 9, 7, 1, 1200, 900, luma)!!
        val row = FloatArray(1200)
        t.rowMeans(10, row)
        assertEquals(2.153059f, row[10], 2e-4f)    // rasterpositie (2,5; 2,5)
        t.rowMeans(3, row)
        assertEquals(2.582358f, row[17], 2e-4f)
        t.rowMeans(27, row)
        assertEquals(2.815889f, row[35], 2e-4f)    // (35, 27) valt buiten het raster en op de hoek terug
        t.rowMeans(0, row)
        assertEquals(0.362619f, row[0], 2e-4f)
        t.rowMeans(899, row)
        assertEquals(2.815889f, row[1199], 2e-4f)
    }

    @Test fun `de piek is het 99,95e percentiel van de omgeving`() {
        val t = LocalTone.build(grid(), 63, 9, 7, 1, 1200, 900, luma)!!
        assertEquals(3.3566f, t.peak, 3.3566f * 0.01f)
        // Een vlak donker beeld heeft geen HDR: de piek blijft op 1.
        val dark = FloatArray(63 * 3) { 0.2f }
        assertEquals(1f, LocalTone.build(dark, 63, 9, 7, 1, 1200, 900, luma)!!.peak, 0f)
    }

    @Test fun `een raster dat niet klopt geeft niets terug`() {
        assertNull(LocalTone.build(FloatArray(30), 10, 9, 7, 1, 100, 100, luma))
        assertNull(LocalTone.build(FloatArray(0), 0, 0, 0, 1, 100, 100, luma))
        assertNotNull(LocalTone.build(grid(), 63, 9, 7, 1, 100, 100, luma))
    }

    // ---- de lokale tone map ----

    private val peak = 3.0f
    private val exposure = 0.88f
    private val knee = 0.5875f

    private fun map(h: FloatArray, s: FloatArray, m: Float): FloatArray {
        val out = FloatArray(3)
        ToneMapper(peak, exposure, knee).mapLocal(h[0], h[1], h[2], s[0], s[1], s[2], m, out, 0)
        return out
    }

    private fun check(h: FloatArray, s: FloatArray, m: Float, expected: FloatArray) {
        val out = map(h, s, m)
        for (c in 0..2) assertEquals("kanaal $c van ${h.toList()} bij M=$m", expected[c], out[c], 3e-4f)
    }

    @Test fun `de top van de schouder volgt de piek`() {
        assertEquals(1f, ToneMapper.lt(1.0f), 0f)
        assertEquals(0.95f, ToneMapper.lt(1.5f), 1e-6f)
        assertEquals(0.9f, ToneMapper.lt(2.5f), 1e-6f)
        assertEquals(0.9f, ToneMapper.lt(4.93f), 1e-6f)
    }

    @Test fun `de lokale tone map volgt het prototype`() {
        check(floatArrayOf(2.0f, 2.2f, 2.6f), floatArrayOf(0.85f, 0.9f, 0.95f), 2.0f, floatArrayOf(0.863455f, 0.922059f, 0.994751f))
        check(floatArrayOf(3.0f, 2.8f, 2.0f), floatArrayOf(0.9f, 0.88f, 0.8f), 3.5f, floatArrayOf(0.789484f, 0.736851f, 0.526322f))
        check(floatArrayOf(1.2f, 1.3f, 1.5f), floatArrayOf(0.6f, 0.65f, 0.7f), 1.0f, floatArrayOf(0.846292f, 0.898588f, 0.975085f))
        check(floatArrayOf(4.5f, 4.0f, 3.0f), floatArrayOf(1.0f, 0.95f, 0.8f), 2.2f, floatArrayOf(0.999999f, 0.999973f, 0.997894f))
        check(floatArrayOf(0.3f, 0.9f, 2.4f), floatArrayOf(0.4f, 0.7f, 0.95f), 1.6f, floatArrayOf(0.196649f, 0.457355f, 0.998902f))
        check(floatArrayOf(1.8f, 1.8f, 1.8f), floatArrayOf(0.95f, 0.95f, 0.95f), 1.9f, floatArrayOf(0.793829f, 0.793829f, 0.793829f))
    }

    @Test fun `in een donkere omgeving geldt de globale curve`() {
        // Omgeving 0,45 (onder M0): ook met gain geen lokale gain; hetzelfde als de globale curve met dezelfde top.
        check(floatArrayOf(2.5f, 2.4f, 2.2f), floatArrayOf(0.8f, 0.8f, 0.8f), 0.45f, floatArrayOf(0.889906f, 0.869088f, 0.827037f))
    }

    @Test fun `zonder gain blijft het zoals de globale curve en het anker`() {
        // Donkere pixel zonder gain (tekst op een omlijsting, omgeving 0,9): alleen de belichting, niet lichter getrokken naar de omgeving.
        check(floatArrayOf(0.05f, 0.05f, 0.05f), floatArrayOf(0.05f, 0.05f, 0.05f), 0.9f, floatArrayOf(0.044f, 0.044f, 0.044f))
        check(floatArrayOf(0.5f, 0.4f, 0.3f), floatArrayOf(0.5f, 0.4f, 0.3f), 0.6f, floatArrayOf(0.44f, 0.352f, 0.264f))
        // De witte rand zelf blijft wit.
        check(floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f), 1.0f, floatArrayOf(1f, 1f, 1f))
    }

    @Test fun `de lichten krijgen ruimte boven de lokale gemiddelden`() {
        // Een pixel iets boven zijn omgeving komt boven die van de omgeving uit (detail), zonder dat een egaal vlak op wit loopt.
        val sdr = floatArrayOf(0.9f, 0.9f, 0.9f)
        val flat = map(floatArrayOf(2.5f, 2.5f, 2.5f), sdr, 2.5f)[0]
        val detail = map(floatArrayOf(2.9f, 2.9f, 2.9f), sdr, 2.5f)[0]
        assertTrue("vlak $flat blijft onder wit", flat < 0.95f)
        assertTrue("detail $detail ligt boven het vlak $flat", detail > flat + 0.02f)
        // Het egale vlak ligt in de buurt van de top, niet op wit zoals bij de globale curve bij de piek.
        assertEquals(ToneMapper.lt(peak), flat, 0.08f)
    }

    @Test fun `sterkte 0 is de identiteit`() {
        // Piek 1, belichting 1, geen gain: de lokale tone map laat de camera-SDR zoals hij is, ook in de lichten (met een omgeving erbij).
        val m = ToneMapper(1f, 1f, 0.55f)
        val out = FloatArray(3)
        for (v in floatArrayOf(0.01f, 0.2f, 0.5f, 0.8f, 0.95f, 1f)) for (env in floatArrayOf(0.1f, 0.7f, 1f)) {
            m.mapLocal(v, v * 0.9f, v * 0.8f, v, v * 0.9f, v * 0.8f, env, out, 0)
            assertEquals("v=$v env=$env", v, out[0], 2e-4f)
            assertEquals("v=$v env=$env", v * 0.9f, out[1], 2e-4f)
            assertEquals("v=$v env=$env", v * 0.8f, out[2], 2e-4f)
        }
    }

    @Test fun `de belichting wordt op de lokale tone map gekozen`() {
        val n = 63
        val hdr = FloatArray(n * 3) { 0.4f + 0.04f * ((it / 3) % 40) }
        val sdr = FloatArray(n * 3) { 0.35f + 0.012f * ((it / 3) % 40) }
        val means = FloatArray(n) { 0.5f + 0.05f * (it % 30) }
        val target = 0.54   // tussen belichting 0,7 (0,5338) en 0,8 (0,5559), uit het prototype
        val t = nl.markmaaktmedia.keeper.scan.ToneMap.Normaal
        val m = ToneMapper.matching(t, 2.5f, luma, hdr, n, target, sdr, 1f, means)
        var sum = 0.0
        val out = FloatArray(3)
        val check = ToneMapper(2.5f, m.exposure, t.knee.toFloat())
        for (i in 0 until n) {
            check.mapLocal(hdr[i * 3], hdr[i * 3 + 1], hdr[i * 3 + 2], sdr[i * 3], sdr[i * 3 + 1], sdr[i * 3 + 2], means[i], out, 0)
            sum += luma[0] * out[0] + luma[1] * out[1] + luma[2] * out[2]
        }
        assertEquals(target, sum / n, target * 0.01)
        assertTrue("belichting ${m.exposure}", m.exposure > 0.7f && m.exposure < 0.8f)
        assertEquals(ToneMapper.liftedKnee(t.knee.toFloat()), m.knee, 1e-6f)
    }
}
