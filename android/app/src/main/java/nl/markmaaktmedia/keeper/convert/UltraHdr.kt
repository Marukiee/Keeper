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
) {
    /**
     * Dezelfde gain map met de HDR-sterkte [strength] (0 tot 1) erin verwerkt, voor HDR behouden: alle gains (log2) keer de sterkte, precies
     * wat [GainMapSampler] in de SDR-omzetting doet. De HDR-offset schuift mee naar de SDR-offset (bij gelijke offsets verandert er niets), zodat
     * sterkte 0 ook bij ongelijke offsets exact de SDR van de camera geeft. De capaciteit (`capMin`/`capMax`) blijft staan: het scherm weegt de gain
     * map daar nog steeds mee, en dan is het resultaat op elk scherm precies [strength] keer de gain van het origineel.
     */
    fun scaled(strength: Float): GainMapMeta {
        val s = strength.coerceIn(0f, 1f)
        return GainMapMeta(
            min = FloatArray(3) { min[it] * s }, max = FloatArray(3) { max[it] * s }, gamma = gamma.copyOf(),
            offSdr = offSdr.copyOf(), offHdr = FloatArray(3) { offSdr[it] + s * (offHdr[it] - offSdr[it]) },
            capMin = capMin, capMax = capMax, baseIsHdr = baseIsHdr,
        )
    }
}

object GainMapXmp {
    /**
     * De hdrgm-XMP van een gain map, in de vorm die de camera's ook schrijven (attributen; lijsten alleen als de kanalen verschillen).
     * Wat [parse] leest, komt hier weer uit.
     */
    fun build(m: GainMapMeta): String {
        val attrs = StringBuilder()
        val lists = StringBuilder()
        fun add(name: String, v: FloatArray) {
            if (v[0] == v[1] && v[1] == v[2]) attrs.append("\n      hdrgm:").append(name).append("=\"").append(number(v[0])).append('"')
            else {
                lists.append("\n      <hdrgm:").append(name).append("><rdf:Seq>")
                v.forEach { lists.append("<rdf:li>").append(number(it)).append("</rdf:li>") }
                lists.append("</rdf:Seq></hdrgm:").append(name).append('>')
            }
        }
        attrs.append("\n      hdrgm:Version=\"1.0\"")
        add("GainMapMin", m.min); add("GainMapMax", m.max); add("Gamma", m.gamma); add("OffsetSDR", m.offSdr); add("OffsetHDR", m.offHdr)
        attrs.append("\n      hdrgm:HDRCapacityMin=\"").append(number(m.capMin)).append('"')
        attrs.append("\n      hdrgm:HDRCapacityMax=\"").append(number(m.capMax)).append('"')
        attrs.append("\n      hdrgm:BaseRenditionIsHDR=\"").append(if (m.baseIsHdr) "True" else "False").append('"')
        val open = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">\n  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "    <rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
        return if (lists.isEmpty()) "$open$attrs/>\n  </rdf:RDF>\n</x:xmpmeta>\n" else "$open$attrs>$lists\n    </rdf:Description>\n  </rdf:RDF>\n</x:xmpmeta>\n"
    }

    /** Een getal in XMP: platte decimale notatie (nooit `1.0E-4`), tot zes decimalen, zonder staartnullen. */
    internal fun number(v: Float): String = java.math.BigDecimal(v.toDouble()).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

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
class GainMapSampler(
    private val meta: GainMapMeta?, private val gm: GainMapImage?, private val width: Int, private val height: Int,
    /**
     * HDR-sterkte, 0 tot 1: hoeveel van de gain map meetelt. Alle gains (log2) worden met deze factor vermenigvuldigd, dus 1 is de volle HDR
     * van de camera en 0 de SDR van de camera. Dit is ook wat [GainMapMeta.scaled] in het bestand zet bij HDR behouden.
     */
    strength: Float = 1f,
) {
    val hasGainMap: Boolean get() = meta != null && gm != null

    private val s = strength.coerceIn(0f, 1f)

    private val lut: Array<FloatArray>? = meta?.let { m ->
        Array(3) { c ->
            FloatArray(LUT_SIZE + 1) { i ->
                val g = i.toFloat() / LUT_SIZE
                val boosted = if (m.gamma[c] == 1f) g else g.toDouble().pow(1.0 / m.gamma[c]).toFloat()
                2.0.pow((s * (m.min[c] + (m.max[c] - m.min[c]) * boosted)).toDouble()).toFloat()
            }
        }
    }

    // Verschillen de twee offsets, dan zou sterkte 0 niet precies de SDR teruggeven; dit rekent dat bij (bij gelijke offsets is het 0).
    private val lift: FloatArray = FloatArray(3) { c -> if (meta == null) 0f else (1f - s) * (meta.offHdr[c] - meta.offSdr[c]) }

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

    /** Werkgeheugen voor [hdrRow]; één per thread. */
    fun newScratch(): FloatArray = FloatArray((gm?.width ?: 0) * (gm?.channels ?: 0))

    fun hdrRow(y: Int, sdr: FloatArray, out: FloatArray) = hdrRow(y, sdr, out, newScratch())

    /**
     * [sdr] en [out] zijn rijen van `width * 3` lineaire floats. Zonder gain map is de uitvoer gelijk aan de invoer.
     * [out] mag dezelfde array zijn als [sdr]. [scratch] komt uit [newScratch] en mag niet door twee threads tegelijk gebruikt worden.
     */
    fun hdrRow(y: Int, sdr: FloatArray, out: FloatArray, scratch: FloatArray) {
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
        // Eerst verticaal mengen: de gain map is veel kleiner dan het beeld, dus dit gebeurt maar één keer per rij en niet per pixel.
        val n = g.width * ch
        val wy1 = 1f - wy
        for (i in 0 until n) scratch[i] = (data[rowLo + i].toInt() and 0xFF) * wy1 + (data[rowHi + i].toInt() and 0xFF) * wy
        val toIndex = LUT_SIZE / 255f
        for (x in 0 until width) {
            val wx = xWeight[x]
            val wx1 = 1f - wx
            val a = xLow[x] * ch
            val b = xHigh[x] * ch
            for (c in 0 until 3) {
                val k = if (ch == 1) 0 else c
                val gv = scratch[a + k] * wx1 + scratch[b + k] * wx
                val factor = tables[c][(gv * toIndex + 0.5f).toInt().coerceIn(0, LUT_SIZE)]
                val i = x * 3 + c
                val hdr = (sdr[i] + m.offSdr[c]) * factor - m.offHdr[c] + lift[c]
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
