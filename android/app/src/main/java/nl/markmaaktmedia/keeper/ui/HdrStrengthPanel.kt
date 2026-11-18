package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.ui.components.HdrStrengthSlider
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton

/**
 * De HDR-sterkte van één foto, onder de foto in de viewer. De foto erboven beweegt live mee ([ZoomablePhoto] rekent het voorbeeld opnieuw bij elke
 * stap); de keuze wordt opgeslagen als je loslaat ([onFinished]) en geldt voor de volgende omzetting van deze foto. [hasOverride] is of de
 * foto een eigen sterkte heeft; "Standaard" haalt die weg en gaat terug naar de sterkte uit Instellingen.
 */
@Composable
internal fun HdrStrengthPanel(
    percent: Int,
    defaultPercent: Int,
    hasOverride: Boolean,
    converting: Boolean,
    loading: Boolean,
    /** Er is geen voorbeeld voor deze foto (de schuif werkt nog wel, voor de omzetting). */
    missing: Boolean,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    onConvert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.viewer_hdr_strength), style = MaterialTheme.typography.titleMedium)
                if (loading) Text(stringResource(R.string.hdr_strength_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (missing) Text(stringResource(R.string.hdr_strength_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SecondaryPillButton(stringResource(R.string.hdr_strength_done), onDone)
        }
        HdrStrengthSlider(percent, onChange, onFinished = onFinished)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasOverride) SecondaryPillButton(stringResource(R.string.hdr_strength_default, defaultPercent), onReset, Modifier.weight(1f))
            PrimaryPillButton(stringResource(R.string.hdr_strength_convert), onConvert, Modifier.weight(1f), enabled = !converting)
        }
    }
}
