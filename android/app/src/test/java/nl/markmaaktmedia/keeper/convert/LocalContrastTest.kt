package nl.markmaaktmedia.keeper.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verwachte waarden komen uit `prototype/tonemap.py` (`lc_factor`, `lc_mean_grid`, `lc_lookup`). */
class LocalContrastTest {
    private val luma = ToneMapper.LUMA_P3

    // Raster van 9 x 7, grijs: 0,2 + 0,1 * ((i * 7 + j * 3) % 11)
    private fun grid(): FloatArray {
        val s = FloatArray(9 * 7 * 3)
        for (j in 0 until 7) for (i in 0 until 9) {
            val v = 0.2f + 0.1f * ((i * 7 + j * 3) % 11)
            for (c in 0..2) s[(j * 9 + i) * 3 + c] = v
        }
        return s
    }

    @Test fun `de gauss geeft hetzelfde als scipy`() {
        val y = FloatArray(63) { 0.2f + 0.1f * (((it % 9) * 7 + (it / 9) * 3) % 11) }
        val b = LocalContrast.blur(y, 9, 7, 1.5f)
        val row0 = floatArrayOf(0.531348f, 0.644724f, 0.732269f, 0.768264f, 0.757523f, 0.727338f, 0.691476f, 0.635941f, 0.563757f)
        for (i in 0 until 9) assertEquals("rij 0 kolom $i", row0[i], b[i], 2e-5f)
        val row3 = floatArrayOf(0.696155f, 0.693541f, 0.688059f, 0.682251f, 0.680305f, 0.685421f, 0.698823f, 0.720907f, 0.749333f)
        for (i in 0 until 9) assertEquals("rij 3 kolom $i", row3[i], b[3 * 9 + i], 2e-5f)
        val row6 = floatArrayOf(0.74112f, 0.742881f, 0.754283f, 0.757101f, 0.752801f, 0.745544f, 0.735159f, 0.743111f, 0.78364f)
        for (i in 0 until 9) assertEquals("rij 6 kolom $i", row6[i], b[6 * 9 + i], 2e-5f)
    }

    @Test fun `de vermenigvuldiger volgt het prototype`() {
        assertEquals(1.6963637f, LocalContrast.factor(3.0f, 1.0f, 1.0f), 1e-4f)
        assertEquals(1.188402f, LocalContrast.factor(2.0f, 0.9f, 1.5f), 1e-4f)
        assertEquals(1.2153703f, LocalContrast.factor(1.5f, 0.8f, 1.0f), 1e-4f)
        assertEquals(0.7821315f, LocalContrast.factor(0.6f, 0.3f, 1.0f), 1e-4f)
        assertEquals(1.2415786f, LocalContrast.factor(2.0f, 1.0f, 0.8f), 1e-4f)
    }

    @Test fun `zonder opticht of in een donkere omgeving blijft een pixel zoals hij is`() {
        assertEquals(1f, LocalContrast.factor(0.4f, 0.8f, 1.0f), 0f)   // donkerder dan de camera
        assertEquals(1f, LocalContrast.factor(1.0f, 0.99f, 1.0f), 0f)  // geen gain (omlijsting, tekst)
        assertEquals(1f, LocalContrast.factor(5.0f, 1.2f, 0.4f), 0f)   // omgeving onder 0,5: schaduw en mids blijven
        assertEquals(1f, LocalContrast.factor(2.4f, 1.0f, 0.3f), 0f)
    }

    @Test fun `de vermenigvuldiger blijft binnen de grenzen`() {
        assertEquals(LocalContrast.SMAX, LocalContrast.factor(40f, 1f, 0.9f), 1e-6f)
        assertEquals(1f / LocalContrast.SMAX, LocalContrast.factor(0.011f, 0.003f, 6f), 1e-6f)
    }

    @Test fun `het omgevingsgemiddelde wordt bilineair opgezocht`() {
        // Breedte 1200 en verkleiningsfactor 1: sigma = 0,005 * 1200 / 4 = 1,5 cellen, zoals in het prototype.
        val lc = LocalContrast.build(grid(), 63, 9, 7, 1, 1200, 900, luma)!!
        val hdr = FloatArray(1200 * 3) { 0.5f }
        val sdr = FloatArray(1200 * 3) { 0.5f }
        // Pixel (10, 10) ligt op rasterpositie (2,5; 2,5); pixel (35, 27) buiten het raster (valt op de hoek terug).
        for (x in intArrayOf(10)) for (c in 0..2) { hdr[x * 3 + c] = 2.0f; sdr[x * 3 + c] = 0.9f }
        lc.adjustRow(10, hdr, sdr)
        assertEquals(2.0f * 1.1296848f, hdr[10 * 3], 2e-4f)
        assertEquals(2.0f * 1.1296848f, hdr[10 * 3 + 2], 2e-4f) // alle kanalen gelijk geschaald: de kleur blijft
        assertEquals(0.5f, hdr[0], 0f)                         // wie niet opgetild is, blijft staan
        val hdr2 = FloatArray(1200 * 3) { 0.5f }
        val sdr2 = FloatArray(1200 * 3) { 0.5f }
        for (c in 0..2) { hdr2[35 * 3 + c] = 2.0f; sdr2[35 * 3 + c] = 0.9f }
        lc.adjustRow(27, hdr2, sdr2)
        assertEquals(2.0f * 1.223974f, hdr2[35 * 3], 2e-4f)
    }

    @Test fun `de witte omlijsting en haar tekst blijven precies zoals ze zijn`() {
        val lc = LocalContrast.build(grid(), 63, 9, 7, 1, 1200, 900, luma)!!
        val frame = FloatArray(1200 * 3) { 1.0f }                          // wit, geen gain
        val text = FloatArray(1200 * 3) { 0.05f }                          // donkere tekst op de omlijsting, ook geen gain
        val sdrFrame = frame.copyOf(); val sdrText = text.copyOf()
        for (y in 0 until 900 step 7) { lc.adjustRow(y, frame, sdrFrame); lc.adjustRow(y, text, sdrText) }
        for (v in frame) assertEquals(1.0f, v, 0f)
        for (v in text) assertEquals(0.05f, v, 0f)
    }

    @Test fun `de steekproef krijgt dezelfde aanpassing met het eigen gemiddelde van elke cel`() {
        val lc = LocalContrast.build(grid(), 63, 9, 7, 1, 1200, 900, luma)!!
        val hdr = FloatArray(63 * 3) { 2.0f }
        val sdr = FloatArray(63 * 3) { 0.9f }
        lc.adjustSamples(hdr, sdr, 63)
        // Cel (0, 0) heeft gemiddelde 0,531348 (bijna onder M0: nauwelijks aanpassing), cel (3, 0) 0,768264.
        assertEquals(2.0f * 1.0046527f, hdr[0], 2e-4f)
        assertEquals(2.0f * LocalContrast.factor(2.0f, 0.9f, 0.768264f), hdr[3 * 3], 2e-4f)
    }

    @Test fun `een raster dat niet klopt geeft niets terug`() {
        assertNull(LocalContrast.build(FloatArray(30), 10, 9, 7, 1, 100, 100, luma))
        assertNull(LocalContrast.build(FloatArray(0), 0, 0, 0, 1, 100, 100, luma))
    }
}
