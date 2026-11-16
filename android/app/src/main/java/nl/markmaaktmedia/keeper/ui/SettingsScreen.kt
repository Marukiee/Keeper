package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import nl.markmaaktmedia.keeper.AppLanguage
import java.util.Locale
import nl.markmaaktmedia.keeper.scan.JpegQuality
import nl.markmaaktmedia.keeper.scan.ToneMap
import nl.markmaaktmedia.keeper.ui.components.ContentRow
import nl.markmaaktmedia.keeper.ui.components.SegmentedPillRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.BuildConfig
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.ui.components.ActionRow
import nl.markmaaktmedia.keeper.ui.components.InfoRow
import nl.markmaaktmedia.keeper.ui.components.SectionHeader
import nl.markmaaktmedia.keeper.ui.components.SettingsGroup
import nl.markmaaktmedia.keeper.ui.components.SwitchRow
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.update.UpdateState

@Composable
fun SettingsScreen(onOpenUpdates: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    val output by prefs.outputFolder.collectAsState(initial = "")
    val lightroom by prefs.lightroomFolder.collectAsState(initial = "")
    val cshot by prefs.includeCshot.collectAsState(initial = true)
    val quality by prefs.quality.collectAsState(initial = 94)
    val toneMapId by prefs.toneMap.collectAsState(initial = "normaal")
    val toneMap = ToneMap.of(toneMapId)

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenHeader(stringResource(R.string.settings_title)) }
        item {
            SectionHeader(stringResource(R.string.settings_folders))
            SettingsGroup {
                InfoRow(0, 3, KeeperIcons.Folder, stringResource(R.string.settings_output), output.trimEnd('/'))
                InfoRow(1, 3, KeeperIcons.Folder, stringResource(R.string.settings_lightroom), lightroom.trimEnd('/'))
                SwitchRow(2, 3, KeeperIcons.Camera, stringResource(R.string.settings_cshot), stringResource(R.string.settings_cshot_sub), cshot, { scope.launch { prefs.setIncludeCshot(it) } })
            }
        }
        item {
            SectionHeader(stringResource(R.string.settings_convert))
            SettingsGroup {
                ContentRow(0, 2, KeeperIcons.Image, stringResource(R.string.settings_quality)) {
                    SegmentedPillRow(JpegQuality.options, quality, { "q$it" }, { scope.launch { prefs.setQuality(it) } }, equalWidth = true)
                    val bpp = JpegQuality.bitsPerPixel(quality)
                    Text(
                        stringResource(R.string.settings_quality_estimate, String.format(Locale.getDefault(), "%.2f", bpp), formatBytes((50_000_000L * bpp / 8).toLong())),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ContentRow(1, 2, KeeperIcons.Tune, stringResource(R.string.settings_tonemap)) {
                    val names = mapOf(
                        ToneMap.Licht to stringResource(R.string.tonemap_licht),
                        ToneMap.Normaal to stringResource(R.string.tonemap_normaal),
                        ToneMap.LichtenBehouden to stringResource(R.string.tonemap_lichten),
                    )
                    SegmentedPillRow(ToneMap.entries, toneMap, { names.getValue(it) }, { scope.launch { prefs.setToneMap(it.id) } }, equalWidth = true)
                    Text(
                        stringResource(when (toneMap) {
                            ToneMap.Licht -> R.string.tonemap_licht_body
                            ToneMap.Normaal -> R.string.tonemap_normaal_body
                            ToneMap.LichtenBehouden -> R.string.tonemap_lichten_body
                        }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.settings_app))
            SettingsGroup {
                ContentRow(0, 2, KeeperIcons.Language, stringResource(R.string.settings_language)) {
                    // Het wisselen start de activity opnieuw op, dus de huidige taal komt altijd uit de actuele configuratie.
                    val current = if (Locale.getDefault().language == "nl") "nl" else "en"
                    val names = mapOf("en" to stringResource(R.string.language_english), "nl" to stringResource(R.string.language_dutch))
                    SegmentedPillRow(listOf("en", "nl"), current, { names.getValue(it) }, { AppLanguage.set(context, it) }, equalWidth = true)
                }
                ActionRow(1, 2, KeeperIcons.Update, stringResource(R.string.settings_updates), stringResource(R.string.settings_updates_sub), onOpenUpdates)
            }
        }
    }
}

/** Versie en updates, zoals in Tandem: handmatig controleren en automatisch één keer per dag. */
@Composable
fun UpdatesScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val auto by graph.prefs.autoUpdateCheck.collectAsState(initial = true)
    val state by graph.updater.state.collectAsState()
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenHeader(stringResource(R.string.settings_updates), onBack = onBack) }
        item {
            SettingsGroup {
                InfoRow(0, 3, KeeperIcons.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
                SwitchRow(1, 3, KeeperIcons.Update, stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_sub), auto, { scope.launch { graph.prefs.setAutoUpdateCheck(it) } })
                ActionRow(
                    2, 3, KeeperIcons.Refresh, stringResource(R.string.settings_check_update),
                    when (val s = state) {
                        is UpdateState.Checking -> stringResource(R.string.settings_checking)
                        is UpdateState.UpToDate -> stringResource(R.string.settings_up_to_date)
                        is UpdateState.Available -> stringResource(R.string.update_available, s.release.versionName)
                        is UpdateState.Failed -> s.reason
                        is UpdateState.Downloading -> stringResource(R.string.update_downloading, (s.progress * 100).toInt())
                        is UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
                        else -> null
                    },
                    { scope.launch { graph.updater.check() } },
                )
            }
        }
    }
}
