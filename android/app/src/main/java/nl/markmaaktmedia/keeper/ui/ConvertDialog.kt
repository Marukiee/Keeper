package nl.markmaaktmedia.keeper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.convert.ConvertReport
import nl.markmaaktmedia.keeper.convert.ConvertState
import nl.markmaaktmedia.keeper.ui.components.KeeperDialog
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.components.WavyProgress
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons

/**
 * Wat je van het omzetten ziet: tijdens de run de voortgang met een Stop-knop, daarna het verslag. Het kruisje sluit alleen het
 * venster; de run gaat door (de melding blijft) en het venster komt niet terug tot de volgende run.
 */
@Composable
fun ConvertDialog(state: ConvertState, outputFolder: String, onStop: () -> Unit, onClose: () -> Unit) {
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
                body = if (state.total > 0) stringResource(R.string.convert_progress, state.index, state.total, state.name) else stringResource(R.string.convert_preparing),
                dismissOnOutsideTap = false,
                closeLabel = stringResource(R.string.settings_back),
                content = { WavyProgress(overall, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant) },
                actions = { SecondaryPillButton(stringResource(if (state.stopping) R.string.convert_stopping else R.string.convert_stop), onStop) },
            )
        }
        is ConvertState.Finished -> {
            val report = state.report
            KeeperDialog(
                title = stringResource(
                    when {
                        report.problem != null -> R.string.convert_stopped
                        report.cancelled -> R.string.convert_stopped
                        report.failures.isNotEmpty() -> R.string.convert_done_with_problems
                        else -> R.string.convert_done
                    },
                ),
                onDismiss = onClose,
                icon = KeeperIcons.CheckCircle,
                body = reportText(report, outputFolder),
                closeLabel = stringResource(R.string.settings_back),
                actions = { PrimaryPillButton(stringResource(R.string.convert_close), onClose) },
            )
        }
    }
}

@Composable
private fun reportText(report: ConvertReport, outputFolder: String): String {
    if (report.problem == ConvertReport.Problem.NoLocationAccess) return stringResource(R.string.convert_need_location)
    val lines = ArrayList<String>()
    if (report.converted > 0) lines += stringResource(R.string.convert_result_converted, report.converted)
    if (report.copied > 0) lines += stringResource(R.string.convert_result_copied, report.copied)
    if (report.skipped > 0) lines += stringResource(R.string.convert_result_skipped, report.skipped)
    if (report.done > 0) {
        if (report.bytesIn > 0) lines += stringResource(R.string.convert_result_size, formatBytes(report.bytesIn), formatBytes(report.bytesOut))
        lines += stringResource(R.string.convert_result_folder, outputFolder.trimEnd('/'))
    }
    if (report.done == 0 && report.failures.isEmpty()) lines += stringResource(R.string.convert_nothing)
    if (report.failures.isNotEmpty()) {
        lines += ""
        lines += stringResource(R.string.convert_failures_title)
        report.failures.take(5).forEach { lines += if (it.name.isEmpty()) it.reason else "${it.name}: ${it.reason}" }
        if (report.failures.size > 5) lines += stringResource(R.string.convert_failures_more, report.failures.size - 5)
    }
    return lines.joinToString("\n")
}
