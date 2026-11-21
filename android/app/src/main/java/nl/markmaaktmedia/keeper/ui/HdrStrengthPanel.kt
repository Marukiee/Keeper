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
import nl.markmaaktmedia.keeper.ui.components.SegmentedPillRow
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton

/**
 * De uitvoer van één foto, onder de foto in de viewer: HDR behouden of SDR ([keepHdr], [onKeepChange]; wordt meteen opgeslagen) en de HDR-sterkte. De foto erboven beweegt live mee ([ZoomablePhoto] rekent het voorbeeld opnieuw bij elke
 * stap); de keuze wordt opgeslagen als je loslaat ([onFinished]) en geldt voor de volgende omzetting van deze foto. [hasOverride] is of de
 * foto een eigen keuze of sterkte heeft; "Standaard" haalt die weg en gaat terug naar Instellingen. De foto erboven toont bij HDR de HDR zelf (waar het
 * scherm dat kan) en bij SDR wat de SDR-omzetting maakt.
 */
@Composable
internal fun HdrStrengthPanel(
    percent: Int,
    defaultPercent: Int,
    keepHdr: Boolean,
    onKeepChange: (Boolean) -> Unit,
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
                Text(stringResource(R.string.viewer_hdr_options), style = MaterialTheme.typography.titleMedium)
                if (loading) Text(stringResource(R.string.hdr_strength_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (missing) Text(stringResource(R.string.hdr_strength_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SecondaryPillButton(stringResource(R.string.hdr_strength_done), onDone)
        }
        val hdrLabel = stringResource(R.string.hdr_strength_hdr)
        val sdrLabel = stringResource(R.string.hdr_strength_sdr)
        SegmentedPillRow(listOf(true, false), keepHdr, { if (it) hdrLabel else sdrLabel }, onKeepChange, equalWidth = true)
        Text(
            stringResource(if (keepHdr) R.string.hdr_kind_hdr_hint else R.string.hdr_kind_sdr_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HdrStrengthSlider(percent, onChange, onFinished = onFinished)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasOverride) SecondaryPillButton(stringResource(R.string.hdr_strength_default, defaultPercent), onReset, Modifier.weight(1f))
            PrimaryPillButton(stringResource(R.string.hdr_strength_convert), onConvert, Modifier.weight(1f), enabled = !converting)
        }
    }
}
