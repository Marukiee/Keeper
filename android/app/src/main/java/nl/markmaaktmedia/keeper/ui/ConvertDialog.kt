package nl.markmaaktmedia.keeper.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.convert.ConvertReport
import nl.markmaaktmedia.keeper.convert.ConvertState
import nl.markmaaktmedia.keeper.convert.How
import nl.markmaaktmedia.keeper.ui.components.KeeperDialog
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.components.WavyProgress
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.SquircleShape

/**
 * Wat je van het omzetten ziet: tijdens de run de foto waar hij mee bezig is (voorbeeld, nummer en naam) met een Pauzeer-knop,
 * daarna het verslag. Na een pauze staat er Doorgaan: wat klaar is blijft klaar, dus doorgaan is gewoon weer starten.
 * Het kruisje sluit alleen het venster; de run gaat door (de melding blijft) en het venster komt niet terug tot de volgende run.
 */
@Composable
fun ConvertDialog(state: ConvertState, outputFolder: String, onPause: () -> Unit, onContinue: () -> Unit, onClose: () -> Unit) {
    when (state) {
        ConvertState.Idle -> Unit
        is ConvertState.Running -> {
            var hidden by remember { mutableStateOf(false) }
            if (hidden) return
            val overall = if (state.total > 0) ((state.index - 1) + state.fraction) / state.total else 0f
            KeeperDialog(
                title = stringResource(R.string.convert_title_running),
                onDismiss = { hidden = true },
                icon = KeeperIcons.Image,
                body = if (state.total > 0) null else stringResource(R.string.convert_preparing),
                dismissOnOutsideTap = false,
                closeLabel = stringResource(R.string.settings_back),
                content = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val item = state.item
                        if (state.total > 0 && item != null) {
                            // Een zachte wissel tussen foto's; binnen één foto blijft het voorbeeld staan.
                            Crossfade(item, label = "convertPhoto") { PhotoThumb(it, 384, Modifier.size(160.dp).clip(SquircleShape(24.dp))) }
                            Text(stringResource(R.string.convert_photo_of, state.index, state.total), style = MaterialTheme.typography.titleMedium)
                            Text(
                                state.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            state.how?.let {
                                Text(stringResource(howLabel(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.alreadyDone > 0) {
                                Text(stringResource(R.string.convert_before, state.alreadyDone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        WavyProgress(overall, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)
                    }
                },
                actions = {
                    SecondaryPillButton(stringResource(if (state.pausing) R.string.convert_pausing else R.string.convert_pause), onPause, icon = KeeperIcons.Pause)
                },
            )
        }
        is ConvertState.Finished -> {
            val report = state.report
            val canContinue = report.paused && report.left > 0 && report.problem == null
            KeeperDialog(
                title = stringResource(
                    when {
                        report.problem != null -> R.string.convert_stopped
                        report.paused -> R.string.convert_paused
                        report.failures.isNotEmpty() -> R.string.convert_done_with_problems
                        else -> R.string.convert_done
                    },
                ),
                onDismiss = onClose,
                icon = if (report.paused) KeeperIcons.Pause else KeeperIcons.CheckCircle,
                body = reportText(report, outputFolder),
                closeLabel = stringResource(R.string.settings_back),
                actions = {
                    if (canContinue) {
                        PrimaryPillButton(stringResource(R.string.convert_continue), onContinue, icon = KeeperIcons.Play)
                        SecondaryPillButton(stringResource(R.string.convert_later), onClose)
                    } else {
                        PrimaryPillButton(stringResource(R.string.convert_close), onClose)
                    }
                },
            )
        }
    }
}

private fun howLabel(how: How): Int = when (how) {
    How.Convert -> R.string.convert_how_convert
    How.CameraVersion -> R.string.convert_how_camera
    How.Copy -> R.string.convert_how_copy
}

@Composable
private fun reportText(report: ConvertReport, outputFolder: String): String {
    if (report.problem == ConvertReport.Problem.NoLocationAccess) return stringResource(R.string.convert_need_location)
    val resources = LocalContext.current.resources
    val lines = ArrayList<String>()
    if (report.converted > 0) lines += stringResource(R.string.convert_result_converted, report.converted)
    if (report.copied > 0) lines += stringResource(R.string.convert_result_copied, report.copied)
    if (report.skipped > 0) lines += stringResource(R.string.convert_result_skipped, report.skipped)
    if (report.done > 0) {
        if (report.bytesIn > 0) lines += stringResource(R.string.convert_result_size, formatBytes(report.bytesIn), formatBytes(report.bytesOut))
        lines += stringResource(R.string.convert_result_folder, outputFolder.trimEnd('/'))
    }
    if (report.paused && report.left > 0) {
        if (lines.isNotEmpty()) lines += ""
        lines += resources.getQuantityString(R.plurals.convert_left, report.left, report.left)
        lines += stringResource(R.string.convert_paused_hint)
    } else if (report.done == 0 && report.failures.isEmpty()) lines += stringResource(R.string.convert_nothing)
    if (report.failures.isNotEmpty()) {
        lines += ""
        lines += stringResource(R.string.convert_failures_title)
        report.failures.take(5).forEach { lines += if (it.name.isEmpty()) it.reason else "${it.name}: ${it.reason}" }
        if (report.failures.size > 5) lines += stringResource(R.string.convert_failures_more, report.failures.size - 5)
    }
    return lines.joinToString("\n")
}
