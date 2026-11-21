package nl.markmaaktmedia.keeper.convert

import java.util.concurrent.ConcurrentHashMap
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Verkleinen met Lanczos (a = 3), op dezelfde manier uitgerekend als Pillow's LANCZOS (steun 3 maal de verkleiningsfactor, de randen worden
 * afgekapt en de gewichten daarna weer op 1 gebracht), zodat het prototype in Python en deze code hetzelfde laten zien.
 */
internal object Lanczos {
    /** Per uitvoerpositie: de eerste bronpositie en de gewichten vanaf daar. */
    class Weights(val start: IntArray, val weights: Array<FloatArray>) {
        val maxSpan: Int = weights.maxOfOrNull { it.size } ?: 0
    }

    private val cache = ConcurrentHashMap<Long, Weights>()

    fun weights(inSize: Int, outSize: Int): Weights = cache.getOrPut(inSize.toLong() shl 32 or outSize.toLong()) { compute(inSize, outSize) }

    private fun kernel(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    private fun compute(inSize: Int, outSize: Int): Weights {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 3.0 * filterScale
        val start = IntArray(outSize)
        val weights = Array(outSize) { FloatArray(0) }
        for (o in 0 until outSize) {
            val center = (o + 0.5) * scale
            val lo = max(0, (center - support + 0.5).toInt())
            val hi = min(inSize, (center + support + 0.5).toInt())
            val n = max(hi - lo, 1)
            val k = DoubleArray(n)
            var sum = 0.0
            for (i in 0 until n) { k[i] = kernel((i + lo - center + 0.5) / filterScale); sum += k[i] }
            start[o] = lo
            weights[o] = FloatArray(n) { (k[it] / sum).toFloat() }
        }
        return Weights(start, weights)
    }

    /** Een los beeld van één kanaal verkleind (of vergroot): [src] is [w] x [h] floats, de uitvoer [outW] x [outH]. */
    fun resize(src: FloatArray, w: Int, h: Int, outW: Int, outH: Int): FloatArray {
        val cols = weights(w, outW)
        val rows = weights(h, outH)
        val tmp = FloatArray(outW * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until outW) {
                val s = cols.start[x]; val wt = cols.weights[x]
                var acc = 0f
                for (i in wt.indices) acc += wt[i] * src[base + s + i]
                tmp[y * outW + x] = acc
            }
        }
        val out = FloatArray(outW * outH)
        for (y in 0 until outH) {
            val s = rows.start[y]; val wt = rows.weights[y]
            for (x in 0 until outW) {
                var acc = 0f
                for (i in wt.indices) acc += wt[i] * tmp[(s + i) * outW + x]
                out[y * outW + x] = acc
            }
        }
        return out
    }
}

/**
 * Verkleint een beeld dat in stroken binnenkomt en geeft de verkleinde rijen door zodra ze klaar zijn, zodat een foto van 200 MP niet in het
 * geheugen hoeft. [src] zijn 8-bit rijen met [channels] kanalen aaneen (RGB). Elke binnenkomende rij gaat meteen horizontaal naar de nieuwe breedte;
 * een uitvoerrij wordt gemaakt zodra alle bronrijen waar hij uit bestaat binnen zijn (zo'n tien rijen).
 *
 * @param maxPush het grootste aantal rijen dat in één keer binnenkomt (de striphoogte).
 */
internal class Resampler(private val inW: Int, private val inH: Int, val outW: Int, val outH: Int, private val channels: Int, maxPush: Int) {
    private val cols = Lanczos.weights(inW, outW)
    private val rows = Lanczos.weights(inH, outH)
    private val ringSize = maxPush + rows.maxSpan + 2
    private val ring = Array(ringSize) { FloatArray(outW * channels) }
    private val outBuf = ByteArray(outW * channels * OUT_ROWS)
    private var received = 0
    private var emitted = 0

    /** Aantal uitvoerrijen dat al doorgegeven is. */
    val rowsOut: Int get() = emitted

    /** [count] rijen uit [src] (elke rij `inW * channels` bytes, aaneen). [emit] krijgt een buffer en het aantal rijen erin; de buffer wordt daarna hergebruikt. */
    fun push(src: ByteArray, count: Int, emit: (ByteArray, Int) -> Unit) {
        check(received + count <= inH) { "meer rijen dan de hoogte" }
        val first = received
        IntStream.range(0, count).parallel().forEach { i -> horizontal(src, i, ring[(first + i) % ringSize]) }
        received += count
        while (emitted < outH && rows.start[emitted] + rows.weights[emitted].size <= received) {
            var n = 0
            while (emitted + n < outH && n < OUT_ROWS && rows.start[emitted + n] + rows.weights[emitted + n].size <= received) n++
            val base = emitted
            IntStream.range(0, n).parallel().forEach { i -> vertical(base + i, outBuf, i * outW * channels) }
            emitted += n
            emit(outBuf, n)
        }
    }

    private fun horizontal(src: ByteArray, row: Int, dst: FloatArray) {
        val base = row * inW * channels
        for (x in 0 until outW) {
            val s = cols.start[x]; val wt = cols.weights[x]
            for (c in 0 until channels) {
                var acc = 0f
                var p = base + s * channels + c
                for (i in wt.indices) { acc += wt[i] * (src[p].toInt() and 0xFF); p += channels }
                dst[x * channels + c] = acc
            }
        }
    }

    private fun vertical(outRow: Int, dst: ByteArray, offset: Int) {
        val s = rows.start[outRow]; val wt = rows.weights[outRow]
        val n = outW * channels
        for (j in 0 until n) {
            var acc = 0f
            for (i in wt.indices) acc += wt[i] * ring[(s + i) % ringSize][j]
            dst[offset + j] = min(255, max(0, acc.roundToInt())).toByte()
        }
    }

    private companion object { const val OUT_ROWS = 64 }
}
