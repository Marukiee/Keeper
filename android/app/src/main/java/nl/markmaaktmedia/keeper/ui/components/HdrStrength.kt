package nl.markmaaktmedia.keeper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import nl.markmaaktmedia.keeper.R
import kotlin.math.roundToInt

/** Stappen van 5%: genoeg om het te voelen, en geen getal dat je zoekt. */
private const val STEPS = 19

/**
 * De HDR-sterkte als schuif van 0 (SDR van de camera) tot 100% (de volle HDR). [onChange] komt bij elke stap, [onFinished] als je loslaat;
 * wie het wil opslaan doet dat bij loslaten, wie live wil meekijken (het voorbeeld) luistert naar [onChange].
 */
@Composable
fun HdrStrengthSlider(percent: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, onFinished: () -> Unit = {}) {
    // Een tik van het systeem bij elke stap (5%), iets steviger op de uiteinden (SDR en de volle HDR): je voelt waar je bent zonder te kijken.
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.hdr_strength_sdr), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.hdr_strength_percent, percent), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.hdr_strength_hdr), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = {
                val value = it.roundToInt()
                if (value != percent) {
                    haptics.performHapticFeedback(if (value == 0 || value == 100) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                }
                onChange(value)
            },
            onValueChangeFinished = onFinished,
            valueRange = 0f..100f,
            steps = STEPS,
        )
    }
}
