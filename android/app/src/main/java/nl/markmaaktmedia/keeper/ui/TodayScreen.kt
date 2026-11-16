package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.UiState
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.components.ActionRow
import nl.markmaaktmedia.keeper.ui.components.EmptyState
import nl.markmaaktmedia.keeper.ui.components.InfoRow
import nl.markmaaktmedia.keeper.ui.components.PillSpinner
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SectionHeader
import nl.markmaaktmedia.keeper.ui.components.SettingsGroup
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.update.UpdateBanner

@Composable
fun TodayScreen(
    state: UiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenEdit: () -> Unit,
    onConvert: () -> Unit,
    converting: Boolean,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { UpdateBanner(Modifier.padding(top = 8.dp)) }
        item { ScreenHeader(stringResource(R.string.today_title), if (state.sinceMs > 0) stringResource(R.string.today_since, formatDay(state.sinceMs)) else null, onBack) }
        when {
            !hasPermission -> item {
                EmptyState(
                    title = stringResource(R.string.permission_title),
                    body = stringResource(R.string.permission_body),
                    icon = KeeperIcons.Image,
                    action = { PrimaryPillButton(stringResource(R.string.permission_action), onRequestPermission) },
                )
            }
            state.loading -> item { PillSpinner(Modifier.padding(32.dp)) }
            state.photos.isEmpty() -> item {
                EmptyState(
                    title = stringResource(R.string.today_empty_title),
                    body = stringResource(R.string.today_empty_body, formatDay(state.sinceMs)),
                    icon = KeeperIcons.Image,
                )
            }
            else -> {
                item { Hero(state) }
                item { SectionHeader(stringResource(R.string.today_section)) }
                item { Breakdown(state, onOpenEdit) }
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryPillButton(stringResource(R.string.today_prepare), onConvert, Modifier.fillMaxWidth(), enabled = state.summary.goingCount > 0 && !converting)
                        Text(stringResource(R.string.today_prepare_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Het getal van de dag: hoeveel er meegaat, en wat dat aan ruimte scheelt. */
@Composable
private fun Hero(state: UiState) {
    val s = state.summary
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.goingCount.toString(), style = MaterialTheme.typography.displayLarge)
            Text(stringResource(if (s.goingCount == 1) R.string.today_going_one else R.string.today_going), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.today_sizes, formatBytes(s.currentBytes), formatBytes(s.expectedBytes)), style = MaterialTheme.typography.bodyLarge)
                if (s.savedBytes > 0) Text(stringResource(R.string.today_saves, formatBytes(s.savedBytes)), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.today_sizes_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Breakdown(state: UiState, onOpenEdit: () -> Unit) {
    val photos = state.photos
    val convert = photos.count { it.needsConversion }
    val asIs = photos.count { it.goesAlong && !it.needsConversion }
    val needsEdit = photos.count { it.status == Status.NeedsEdit }
    val notKept = photos.count { it.status == Status.NotKept }
    val skipped = photos.count { it.status == Status.Skipped }
    val left = photos.count { it.status == Status.RawEdited || it.status == Status.OriginalReplaced || it.status == Status.OriginalExported }
    val done = photos.count { it.status == Status.Done }
    SettingsGroup {
        val rows = buildList<@Composable (Int, Int) -> Unit> {
            add { i, n -> InfoRow(i, n, KeeperIcons.Update, stringResource(R.string.today_convert), convert.toString()) }
            add { i, n -> InfoRow(i, n, KeeperIcons.Check, stringResource(R.string.today_asis), asIs.toString()) }
            add { i, n ->
                ActionRow(i, n, KeeperIcons.Tune, stringResource(R.string.today_needs_edit), stringResource(R.string.today_needs_edit_sub), onOpenEdit,
                    trailing = { Text(needsEdit.toString(), style = MaterialTheme.typography.titleMedium) })
            }
            if (notKept > 0) add { i, n -> InfoRow(i, n, KeeperIcons.Close, stringResource(R.string.today_not_kept), notKept.toString()) }
            if (skipped > 0) add { i, n -> InfoRow(i, n, KeeperIcons.Close, stringResource(R.string.today_skipped), skipped.toString()) }
            if (left > 0) add { i, n -> InfoRow(i, n, KeeperIcons.Info, stringResource(R.string.today_left), left.toString()) }
            if (done > 0) add { i, n -> InfoRow(i, n, KeeperIcons.CheckCircle, stringResource(R.string.today_done), done.toString()) }
        }
        rows.forEachIndexed { index, row -> row(index, rows.size) }
    }
}
