package nl.markmaaktmedia.keeper.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.ui.components.KeeperDialog
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons

/**
 * Wordt getoond als je een origineel bewaart waarvan al een bewerkte versie bestaat (Lightroom-export of Galerij-kopie). De keuze is
 * of Keeper dat origineel ook zelf exporteert (camerafilter, verkleind: dan zijn er twee versies van dezelfde foto) of dat alleen de
 * bewerkte versie meegaat. [count] is het aantal van zulke foto's in de keuze.
 */
@Composable
fun EditedKeepDialog(count: Int, onEditedOnly: () -> Unit, onExportToo: () -> Unit, onDismiss: () -> Unit) {
    KeeperDialog(
        title = stringResource(R.string.edited_keep_title),
        onDismiss = onDismiss,
        icon = KeeperIcons.Check,
        body = if (count == 1) stringResource(R.string.edited_keep_body_one) else stringResource(R.string.edited_keep_body_many, count),
        closeLabel = stringResource(R.string.settings_back),
        actions = {
            SecondaryPillButton(stringResource(R.string.edited_keep_also), onExportToo)
            PrimaryPillButton(stringResource(R.string.edited_keep_only), onEditedOnly)
        },
    )
}
