package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.ui.components.HdrStrengthSlider
import nl.markmaaktmedia.keeper.ui.components.KeeperIconButton
import nl.markmaaktmedia.keeper.ui.components.SegmentedPillRow
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons

/**
 * De uitvoer van één foto, onder de foto in de viewer: HDR behouden of SDR ([keepHdr], [onKeepChange]; wordt meteen opgeslagen) en de HDR-sterkte. Het
 * paneel is compact en staat standaard onder elke HDR-foto, ook tijdens het doorvegen, zodat je foto na foto snel kunt aanpassen. De foto erboven
 * beweegt live mee ([ZoomablePhoto] rekent het voorbeeld opnieuw bij elke stap); de sterkte wordt opgeslagen als je loslaat ([onFinished]) en geldt
 * voor de volgende omzetting van deze foto. [hasOverride] is of de foto een eigen keuze of sterkte heeft; het pijltje ([onReset]) haalt die weg en
 * gaat terug naar Instellingen. De foto erboven toont bij HDR de HDR zelf (waar het scherm dat kan) en bij SDR wat de SDR-omzetting maakt.
 * De hoogte verandert niet met de toestand, zodat de foto niet verspringt tijdens het doorvegen.
 */
@Composable
internal fun HdrStrengthPanel(
    percent: Int,
    defaultPercent: Int,
    keepHdr: Boolean,
    onKeepChange: (Boolean) -> Unit,
    hasOverride: Boolean,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val hdrLabel = stringResource(R.string.hdr_strength_hdr)
            val sdrLabel = stringResource(R.string.hdr_strength_sdr)
            SegmentedPillRow(listOf(true, false), keepHdr, { if (it) hdrLabel else sdrLabel }, onKeepChange, Modifier.weight(1f), equalWidth = true)
            // Terug naar de standaard (Instellingen). Staat er altijd, zodat het paneel niet verspringt; zonder eigen keuze is hij onzichtbaar en uit.
            KeeperIconButton(
                KeeperIcons.Refresh, stringResource(R.string.hdr_strength_default, defaultPercent), onReset,
                Modifier.alpha(if (hasOverride) 1f else 0f), size = 44, iconSize = 20, enabled = hasOverride,
                background = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        HdrStrengthSlider(percent, onChange, onFinished = onFinished)
    }
}
