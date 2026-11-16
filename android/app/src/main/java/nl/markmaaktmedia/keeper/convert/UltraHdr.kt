package nl.markmaaktmedia.keeper.convert

import java.io.InputStream
import kotlin.math.pow

/**
 * Parameters van de gain map (Ultra HDR, ISO 21496-1) uit de hdrgm-XMP. Alles per kanaal (RGB); staat er één waarde,
 * dan geldt die voor alle drie. Waarden zijn log2.
 */
class GainMapMeta(
    val min: FloatArray, val max: FloatArray, val gamma: FloatArray,
    val offSdr: FloatArray, val offHdr: FloatArray,
    val capMin: Float, val capMax: Float, val baseIsHdr: Boolean,
)

object GainMapXmp {
    /** Null als dit geen hdrgm-XMP is. Kent zowel attributen (`hdrgm:GainMapMax="2.3"`) als lijsten (`<rdf:Seq><rdf:li>`). */
    fun parse(xmp: String): GainMapMeta? {
        if (!xmp.contains("hdrgm:")) return null
        fun values(name: String, default: String): List<String> {
            Regex("hdrgm:$name=\"([^\"]+)\"").find(xmp)?.let { return listOf(it.groupValues[1].trim()) }
            Regex("<hdrgm:$name>(.*?)</hdrgm:$name>", RegexOption.DOT_MATCHES_ALL).find(xmp)?.let { m ->
                val items = Regex("<rdf:li>([^<]+)</rdf:li>").findAll(m.groupValues[1]).map { it.groupValues[1].trim() }.toList()
                return items.ifEmpty { listOf(m.groupValues[1].trim()) }
            }
            return listOf(default)
        }
        fun per3(name: String, default: String): FloatArray? {
            val v = values(name, default).map { it.toFloatOrNull() ?: return null }
            return FloatArray(3) { v[if (v.size >= 3) it else 0] }
        }
        return GainMapMeta(
            min = per3("GainMapMin", "0") ?: return null,
            max = per3("GainMapMax", "1") ?: return null,
            gamma = per3("Gamma", "1") ?: return null,
            offSdr = per3("OffsetSDR", "0.015625") ?: return null,
            offHdr = per3("OffsetHDR", "0.015625") ?: return null,
            capMin = values("HDRCapacityMin", "0")[0].toFloatOrNull() ?: 0f,
            capMax = values("HDRCapacityMax", "1")[0].toFloatOrNull() ?: 1f,
            baseIsHdr = values("BaseRenditionIsHDR", "False")[0].equals("true", ignoreCase = true),
        )
    }
}

/** Waar het hoofdbeeld en de gain map in het bestand zitten, plus de kop van allebei. */
class UltraHdrLayout(
    val primary: JpegHead, val primaryLength: Long,
    val gainStart: Long, val gainLength: Long, val gain: JpegHead,
    val meta: GainMapMeta,
)

object UltraHdrReader {
    /** [open] moet bij elke aanroep een nieuwe stroom vanaf het begin van het bestand geven. Null als er geen bruikbare gain map in zit. */
    fun read(open: () -> InputStream): UltraHdrLayout? {
        val head = open().use { JpegParser.readHead(it) } ?: return null
        val images = head.mpfImages() ?: return null
        if (images.size < 2) return null
        for (k in 1 until images.size) {
            val (start, length) = images[k]
            val gainHead = open().use { ins -> if (!ins.skipExactly(start)) null else JpegParser.readHead(ins) } ?: continue
            val meta = gainHead.xmp()?.let { GainMapXmp.parse(it) } ?: continue
            return UltraHdrLayout(head, images[0].second, start, length, gainHead, meta)
        }
        return null
    }
}

internal fun InputStream.skipExactly(count: Long): Boolean {
    var left = count
    while (left > 0) {
        val n = skip(left)
        if (n > 0) left -= n else if (read() < 0) return false else left--
    }
    return true
}

/** De gain map als losse bytes, `channels` is 1 (grijs) of 3 (RGB), rij na rij. */
class GainMapImage(val width: Int, val height: Int, val channels: Int, val data: ByteArray)

/**
 * Zet een rij SDR (lineair, in het gamut van het basisbeeld) om naar HDR met de gain map, rij voor rij zodat een foto
 * van 200 MP nooit als geheel in float in het geheugen komt. Dezelfde formule als het prototype:
 * `HDR = (sdr + offSdr) * 2^(min + (max - min) * G^(1/gamma)) - offHdr`, G bilineair opgeschaald naar het basisbeeld.
 */
class GainMapSampler(private val meta: GainMapMeta?, private val gm: GainMapImage?, private val width: Int, private val height: Int) {
    val hasGainMap: Boolean get() = meta != null && gm != null

    private val lut: Array<FloatArray>? = meta?.let { m ->
        Array(3) { c ->
            FloatArray(LUT_SIZE + 1) { i ->
                val g = i.toFloat() / LUT_SIZE
                val boosted = if (m.gamma[c] == 1f) g else g.toDouble().pow(1.0 / m.gamma[c]).toFloat()
                2.0.pow((m.min[c] + (m.max[c] - m.min[c]) * boosted).toDouble()).toFloat()
            }
        }
    }

    // Per kolom de twee gain-map-kolommen en het gewicht, eenmalig.
    private val xLow = IntArray(width)
    private val xHigh = IntArray(width)
    private val xWeight = FloatArray(width)

    init {
        val g = gm
        if (g != null) for (x in 0 until width) {
            val pos = ((x + 0.5f) * g.width / width - 0.5f).coerceIn(0f, (g.width - 1).toFloat())
            val lo = pos.toInt()
            xLow[x] = lo
            xHigh[x] = minOf(lo + 1, g.width - 1)
            xWeight[x] = pos - lo
        }
    }

    /**
     * [sdr] en [out] zijn rijen van `width * 3` lineaire floats. Zonder gain map is de uitvoer gelijk aan de invoer.
     * [out] mag dezelfde array zijn als [sdr].
     */
    fun hdrRow(y: Int, sdr: FloatArray, out: FloatArray) {
        val m = meta
        val g = gm
        val tables = lut
        if (m == null || g == null || tables == null) { if (out !== sdr) System.arraycopy(sdr, 0, out, 0, width * 3); return }
        val pos = ((y + 0.5f) * g.height / height - 0.5f).coerceIn(0f, (g.height - 1).toFloat())
        val yl = pos.toInt()
        val yh = minOf(yl + 1, g.height - 1)
        val wy = pos - yl
        val ch = g.channels
        val rowLo = yl * g.width * ch
        val rowHi = yh * g.width * ch
        val data = g.data
        for (x in 0 until width) {
            val wx = xWeight[x]
            val a = xLow[x] * ch
            val b = xHigh[x] * ch
            for (c in 0 until 3) {
                val k = if (ch == 1) 0 else c
                val top = (data[rowLo + a + k].toInt() and 0xFF) * (1 - wx) + (data[rowLo + b + k].toInt() and 0xFF) * wx
                val bottom = (data[rowHi + a + k].toInt() and 0xFF) * (1 - wx) + (data[rowHi + b + k].toInt() and 0xFF) * wx
                val gv = (top * (1 - wy) + bottom * wy) * (1f / 255f)
                val factor = tables[c][(gv * LUT_SIZE + 0.5f).toInt().coerceIn(0, LUT_SIZE)]
                val i = x * 3 + c
                val hdr = (sdr[i] + m.offSdr[c]) * factor - m.offHdr[c]
                out[i] = if (hdr > 0f) hdr else 0f
            }
        }
    }

    companion object {
        private const val LUT_SIZE = 4096
    }
}

/** sRGB-transfer als tabel, voor 8-bit in en uit. */
object Srgb {
    /** 8-bit gecodeerd naar lineair. */
    val toLinear = FloatArray(256) { v ->
        val s = v / 255f
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private const val ENC_SIZE = 32768
    private val enc = ByteArray(ENC_SIZE + 1) { i ->
        val l = i.toFloat() / ENC_SIZE
        val s = if (l <= 0.0031308f) l * 12.92f else (1.055 * l.toDouble().pow(1 / 2.4) - 0.055).toFloat()
        (s * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /** Lineair (0..1) naar 8-bit gecodeerd, afgerond. */
    fun toByte(linear: Float): Int = enc[(linear * ENC_SIZE + 0.5f).toInt().coerceIn(0, ENC_SIZE)].toInt() and 0xFF
}
