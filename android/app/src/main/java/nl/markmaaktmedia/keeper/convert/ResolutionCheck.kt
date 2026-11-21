package nl.markmaaktmedia.keeper.convert

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** Hoeveel pixels de uitvoer krijgt: alle, de helft (een factor 0,71 per kant), een kwart (0,5 per kant), of laten bepalen per foto. */
enum class Resolution(val id: String, val linear: Float?) {
    /** Altijd alle pixels. */
    Full("full", 1f),
    /** Per foto: de helft van de pixels als daar niets zichtbaars mee verloren gaat (zie [ResolutionCheck]), anders alle. */
    Auto("auto", null),
    /** Alleen om te testen (debug-ontvanger): altijd de helft van de pixels. */
    Half("half", ResolutionCheck.HALF),
    /** Alleen om te testen: altijd een kwart van de pixels. */
    Quarter("quarter", 0.5f);

    companion object { fun of(id: String?): Resolution = entries.firstOrNull { it.id == id } ?: Auto }
}

/**
 * Of een foto de resolutie van de camera echt nodig heeft. Een Oppo maakt van elke foto een bestand van 50 MP, maar daar zit vaak minder
 * detail in dan dat. De meting: de meest gedetailleerde stukjes van de foto (de plekken waar het gaat om fijne tekening) gaan op luminantie
 * naar de helft van de pixels en weer terug, en het verschil met het origineel (PSNR) zegt wat de helft weggooit. Is dat overal heel klein,
 * dan draagt de rest van de pixels niets dat te zien is en kan de foto op de helft van de pixels bewaard worden.
 *
 * Gemeten op 45 foto's van de Oppo (2026-11-21, prototype in Python met stukken van 768 pixels): elke foto verloor bij de helft van de pixels minder dan
 * 43,5 dB aan PSNR (mediaan 51); bij een kwart tot 38,7 dB. Waar de drempel precies hoort, bepaalt wat Mark op de testfoto's zelf nog ziet (zie CLAUDE.md).
 */
object ResolutionCheck {
    /** Een factor 1/sqrt(2) per kant: de helft van de pixels (50 MP wordt 25 MP). */
    const val HALF = 0.70710678f
    /** Onder dit aantal pixels bewaren we de foto zoals hij is: een foto van 12 MP is al klein. */
    const val MIN_PIXELS = 30_000_000L
    /** Een stukje is een vierkant van zoveel pixels, uitgelijnd op de stroken van de omzetting. */
    const val CELL = 256
    /** Aan de rand van een stukje klopt de verkleining niet (er ontbreekt omgeving); de meting kijkt alleen naar het midden. */
    private const val MARGIN = 32
    /** De score is de PSNR die het slechtste vijfde van de gedetailleerde stukjes haalt: een foto telt niet mee als maar één hoekje fijn detail heeft. */
    private const val PERCENTILE = 0.2
    /** Boven deze score (dB) gaat er bij de helft van de pixels niets zichtbaars verloren; te kalibreren op wat Mark ziet. */
    const val THRESHOLD_DB = 42.0

    class Verdict(val scoreDb: Double, val halve: Boolean)

    /** Van de score naar de uitkomst; [pixels] is het aantal pixels van de foto. */
    fun decide(pixels: Long, scoreDb: Double): Verdict = Verdict(scoreDb, pixels >= MIN_PIXELS && scoreDb >= THRESHOLD_DB)

    /**
     * Verzamelt de [keep] stukjes met het meeste detail uit een foto die in stroken van [CELL] rijen binnenkomt. Een stukje komt in aanmerking
     * op de gemiddelde helling van de luminantie (hoe meer fijne tekening, hoe hoger); alleen die stukjes worden bewaard, de rest is zo weer weg.
     */
    class Sampler(private val width: Int, private val keep: Int) {
        private class Cell(val gradient: Float, val luma: ByteArray)
        private val cells = ArrayList<Cell>()
        private val columns = width / CELL
        private val scratch = ByteArray(CELL * CELL)

        /** Eén strook van [CELL] rijen, ARGB_8888 ([raw], [rowBytes] per rij; de bytes liggen als R, G, B, A). */
        fun addStrip(raw: ByteArray, rowBytes: Int) {
            for (c in 0 until columns) {
                for (y in 0 until CELL) {
                    var p = y * rowBytes + c * CELL * 4
                    var o = y * CELL
                    for (x in 0 until CELL) {
                        // Luminantie zoals Pillow's "L": (299 R + 587 G + 114 B) / 1000.
                        scratch[o++] = ((299 * (raw[p].toInt() and 0xFF) + 587 * (raw[p + 1].toInt() and 0xFF) + 114 * (raw[p + 2].toInt() and 0xFF)) / 1000).toByte()
                        p += 4
                    }
                }
                val g = gradient(scratch)
                if (cells.size < keep) cells += Cell(g, scratch.copyOf())
                else {
                    var lowest = 0
                    for (i in 1 until cells.size) if (cells[i].gradient < cells[lowest].gradient) lowest = i
                    if (g > cells[lowest].gradient) cells[lowest] = Cell(g, scratch.copyOf())
                }
            }
        }

        /** De score van de verzamelde stukjes, of null als er geen was (een foto smaller dan één stukje). */
        fun score(): Double? {
            if (cells.isEmpty()) return null
            val psnr = DoubleArray(cells.size)
            IntStream.range(0, cells.size).parallel().forEach { psnr[it] = lossAtHalf(cells[it].luma) }
            psnr.sort()
            return psnr[((psnr.size - 1) * PERCENTILE).toInt()]
        }
    }

    /** Het aantal stukjes dat een foto van [width] x [height] geeft als elke [stride]e strook meetelt. */
    fun cellCount(width: Int, height: Int, stride: Int): Int = ((height / CELL + stride - 1) / stride) * (width / CELL)

    /** Hoeveel stukjes bewaard worden: het zesde deel met het meeste detail, minstens acht. */
    fun keepFor(cells: Int): Int = max(8, cells / 6)

    private fun gradient(luma: ByteArray): Float {
        var sum = 0L
        for (y in MARGIN until CELL - MARGIN) for (x in MARGIN until CELL - MARGIN) {
            val p = y * CELL + x
            val v = luma[p].toInt() and 0xFF
            sum += abs(v - (luma[p + 1].toInt() and 0xFF)) + abs(v - (luma[p + CELL].toInt() and 0xFF))
        }
        return sum.toFloat() / ((CELL - 2 * MARGIN) * (CELL - 2 * MARGIN))
    }

    /** PSNR (dB) tussen een stukje en datzelfde stukje op de helft van de pixels en weer terug, in het midden van het stukje. */
    fun lossAtHalf(luma: ByteArray): Double {
        val src = FloatArray(CELL * CELL) { (luma[it].toInt() and 0xFF).toFloat() }
        val small = (CELL * HALF).roundToInt()
        val back = Lanczos.resize(Lanczos.resize(src, CELL, CELL, small, small), small, small, CELL, CELL)
        var sum = 0.0
        for (y in MARGIN until CELL - MARGIN) for (x in MARGIN until CELL - MARGIN) {
            val d = (src[y * CELL + x] - back[y * CELL + x]).toDouble()
            sum += d * d
        }
        val mse = sum / ((CELL - 2 * MARGIN) * (CELL - 2 * MARGIN))
        return if (mse < 1e-9) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }
}
