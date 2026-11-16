package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.BuildConfig
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.lightroom.LookPreset
import nl.markmaaktmedia.keeper.scan.ToneMap
import nl.markmaaktmedia.keeper.ui.components.ActionRow
import nl.markmaaktmedia.keeper.ui.components.InfoRow
import nl.markmaaktmedia.keeper.ui.components.SettingsGroup
import nl.markmaaktmedia.keeper.ui.components.SwitchRow
import nl.markmaaktmedia.keeper.ui.settings.AppearancePage
import nl.markmaaktmedia.keeper.ui.settings.ConvertPage
import nl.markmaaktmedia.keeper.ui.settings.FoldersPage
import nl.markmaaktmedia.keeper.ui.settings.LookPage
import nl.markmaaktmedia.keeper.ui.settings.RawPage
import nl.markmaaktmedia.keeper.ui.settings.SettingsPage
import nl.markmaaktmedia.keeper.ui.settings.seedLabel
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.LocalAppearance
import nl.markmaaktmedia.keeper.ui.theme.ThemeMode
import nl.markmaaktmedia.keeper.update.UpdateState

/** De categorieën op het overzicht, in groepen zoals in Tandem; elke opent een eigen pagina. */
private enum class SettingsCategory(val page: SettingsPage, val title: Int) {
    Look(SettingsPage.Look, R.string.settings_look),
    Folders(SettingsPage.Folders, R.string.settings_folders),
    Convert(SettingsPage.Convert, R.string.settings_convert),
    Raw(SettingsPage.Raw, R.string.settings_raw),
    Updates(SettingsPage.Updates, R.string.settings_updates),
}

private val Groups = listOf(
    listOf(SettingsCategory.Look),
    listOf(SettingsCategory.Folders, SettingsCategory.Convert, SettingsCategory.Raw),
    listOf(SettingsCategory.Updates),
)

@Composable
private fun SettingsCategory.icon(): Painter = when (this) {
    SettingsCategory.Look -> KeeperIcons.Palette
    SettingsCategory.Folders -> KeeperIcons.Folder
    SettingsCategory.Convert -> KeeperIcons.Tune
    SettingsCategory.Raw -> KeeperIcons.Image
    SettingsCategory.Updates -> KeeperIcons.Update
}

/**
 * Het regeltje onder elke categorie, live uit waar de instellingen bewaard staan: wat er gekozen is, zodat je het ziet zonder de
 * pagina te openen.
 */
@Composable
private fun rememberCategorySummaries(): Map<SettingsCategory, String> {
    val graph = LocalContext.current.graph
    val prefs = graph.prefs
    val appearance = LocalAppearance.current
    val locale = LocalConfiguration.current.locales[0]
    val languageName = stringResource(if (locale.language == "nl") R.string.language_dutch else R.string.language_english)
    // Het systeem volgen is de standaard en zegt niets, dus alleen een gekozen thema wordt genoemd.
    val mode = when (appearance.mode) {
        ThemeMode.LIGHT -> stringResource(R.string.appearance_light)
        ThemeMode.DARK -> stringResource(R.string.appearance_dark)
        ThemeMode.SYSTEM -> null
    }
    val look = listOfNotNull(mode, stringResource(seedLabel(appearance.seed)), languageName).joinToString(", ")

    val output by prefs.outputFolder.collectAsState(initial = "")
    val quality by prefs.quality.collectAsState(initial = 94)
    val toneMapId by prefs.toneMap.collectAsState(initial = "normaal")
    val toneName = stringResource(
        when (ToneMap.of(toneMapId)) {
            ToneMap.Licht -> R.string.tonemap_licht
            ToneMap.Normaal -> R.string.tonemap_normaal
            ToneMap.LichtenBehouden -> R.string.tonemap_lichten
        },
    )
    val lookDefault by prefs.lookDefault.collectAsState(initial = "")
    val preset = LookPreset.of(lookDefault)?.label ?: stringResource(R.string.look_none)
    val update by graph.updater.state.collectAsState()
    val updates = if (update is UpdateState.Available) stringResource(R.string.settings_updates_summary_available, BuildConfig.VERSION_NAME)
        else stringResource(R.string.settings_updates_summary, BuildConfig.VERSION_NAME)

    return mapOf(
        SettingsCategory.Look to look,
        SettingsCategory.Folders to output.trimEnd('/'),
        SettingsCategory.Convert to stringResource(R.string.settings_convert_summary, quality, toneName),
        SettingsCategory.Raw to preset,
        SettingsCategory.Updates to updates,
    )
}

/** Het tabblad Instellingen: de categorieën met hun regeltje. Een tik opent de pagina; wat erop staat staat in `ui/settings`. */
@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit, contentPadding: PaddingValues) {
    val summaries = rememberCategorySummaries()
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(stringResource(R.string.settings_title)) }
        items(Groups.size) { group ->
            SettingsGroup {
                Groups[group].forEachIndexed { position, category ->
                    ActionRow(position, Groups[group].size, category.icon(), stringResource(category.title), summaries[category].orEmpty(), { onOpen(category.page) })
                }
            }
        }
    }
}

/** De pagina die bij [page] hoort. Terug gaat van Uiterlijk naar Uiterlijk en taal, en van de rest naar het overzicht. */
@Composable
fun SettingsPageScreen(page: SettingsPage, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit, contentPadding: PaddingValues) {
    when (page) {
        SettingsPage.Look -> LookPage(onBack, onOpen, contentPadding)
        SettingsPage.Appearance -> AppearancePage(onBack, contentPadding)
        SettingsPage.Folders -> FoldersPage(onBack, contentPadding)
        SettingsPage.Convert -> ConvertPage(onBack, contentPadding)
        SettingsPage.Raw -> RawPage(onBack, contentPadding)
        SettingsPage.Updates -> UpdatesScreen(onBack, contentPadding)
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
