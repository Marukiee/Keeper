package nl.markmaaktmedia.keeper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A progress bar in the shape of Material 3 Expressive: what is done is a wave, what is left is a straight line. The wave
 * does not move on its own, so nothing keeps drawing while a transfer waits; it follows the progress and nothing else.
 * The library has its own, but only in versions that are not out yet.
 */
@Composable
fun WavyProgress(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 4.dp,
    amplitude: Dp = 2.5.dp,
    wavelength: Dp = 22.dp,
) {
    Canvas(modifier.fillMaxWidth().height(stroke + amplitude * 2 + 2.dp)) {
        val width = size.width
        val middle = size.height / 2
        val line = stroke.toPx()
        val reach = (width * fraction.coerceIn(0f, 1f)).coerceAtLeast(line)
        // The part still to do. As in Material 3 Expressive it does not touch the wave: there is a gap of a few dp between the two round
        // ends, and a small dot marks where the track stops.
        val gap = GAP.toPx()
        val trackStart = reach + line + gap
        if (trackStart < width - line / 2) {
            drawLine(trackColor, Offset(trackStart, middle), Offset(width - line / 2, middle), strokeWidth = line, cap = StrokeCap.Round)
            drawCircle(color, radius = line * 0.3f, center = Offset(width - line / 2, middle))
        }
        // The part that is done, as a wave that is flat where it starts and where it ends, so it does not jump at the ends.
        val path = Path()
        val a = amplitude.toPx()
        val length = wavelength.toPx()
        var x = 0f
        path.moveTo(0f, middle)
        while (x <= reach) {
            val edge = minOf(1f, minOf(x, reach - x) / (length / 2))
            path.lineTo(x, middle + a * edge * sin(2 * PI * x / length).toFloat())
            x += 2f
        }
        path.lineTo(reach, middle)
        drawPath(path, color, style = Stroke(width = line, cap = StrokeCap.Round))
    }
}

/** Space between the end of the wave and the start of the straight track. */
private val GAP = 4.dp
