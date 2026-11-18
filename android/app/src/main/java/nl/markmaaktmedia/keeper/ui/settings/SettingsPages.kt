package nl.markmaaktmedia.keeper.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.AppLanguage
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.lightroom.LookPreset
import nl.markmaaktmedia.keeper.scan.JpegQuality
import nl.markmaaktmedia.keeper.scan.ToneMap
import nl.markmaaktmedia.keeper.ui.ScreenHeader
import nl.markmaaktmedia.keeper.ui.components.ActionRow
import nl.markmaaktmedia.keeper.ui.components.ContentRow
import nl.markmaaktmedia.keeper.ui.components.HdrStrengthSlider
import nl.markmaaktmedia.keeper.ui.components.InfoRow
import nl.markmaaktmedia.keeper.ui.components.SectionHeader
import nl.markmaaktmedia.keeper.ui.components.SegmentedPillRow
import nl.markmaaktmedia.keeper.ui.components.SettingsGroup
import nl.markmaaktmedia.keeper.ui.components.SwitchRow
import nl.markmaaktmedia.keeper.ui.formatBytes
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.LocalAppearance
import nl.markmaaktmedia.keeper.ui.theme.ThemeMode
import java.util.Locale

/** Eén pagina onder Instellingen. [Appearance] hoort bij [Look] en gaat bij Terug daarheen. */
enum class SettingsPage { Look, Appearance, Folders, Convert, Raw, Updates }

/**
 * Het kader van elke instellingenpagina, zoals de oudere schermen: terugknop en titel, daaronder de groepen. Het scrolt als één kolom,
 * en de onderrand laat ruimte voor de navigatiebalk.
 */
@Composable
internal fun SettingsPageFrame(title: String, onBack: () -> Unit, contentPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScreenHeader(title, onBack = onBack)
        content()
    }
}

/** Licht, donker en puur zwart zijn wat het vaakst veranderd wordt, dus ze staan hier en niet een pagina verder. */
@Composable
internal fun LookPage(onBack: () -> Unit, onOpen: (SettingsPage) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    val appearance = LocalAppearance.current

    SettingsPageFrame(stringResource(R.string.settings_look), onBack, contentPadding) {
        SectionHeader(stringResource(R.string.appearance_theme))
        SettingsGroup {
            ContentRow(0, 2, KeeperIcons.DarkMode, stringResource(R.string.appearance_mode)) {
                val names = mapOf(
                    ThemeMode.SYSTEM to stringResource(R.string.appearance_system),
                    ThemeMode.LIGHT to stringResource(R.string.appearance_light),
                    ThemeMode.DARK to stringResource(R.string.appearance_dark),
                )
                SegmentedPillRow(ThemeMode.entries, appearance.mode, { names.getValue(it) }, { scope.launch { prefs.setThemeMode(it) } }, equalWidth = true)
            }
            SwitchRow(
                1, 2, KeeperIcons.DarkMode, stringResource(R.string.appearance_black), stringResource(R.string.appearance_black_sub),
                appearance.pureBlack, { scope.launch { prefs.setPureBlack(it) } },
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            ActionRow(0, 2, KeeperIcons.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub), { onOpen(SettingsPage.Appearance) })
            ContentRow(1, 2, KeeperIcons.Language, stringResource(R.string.settings_language)) {
                // Het wisselen start de activity opnieuw op, dus de huidige taal komt altijd uit de actuele configuratie.
                val current = if (Locale.getDefault().language == "nl") "nl" else "en"
                val names = mapOf("en" to stringResource(R.string.language_english), "nl" to stringResource(R.string.language_dutch))
                SegmentedPillRow(listOf("en", "nl"), current, { names.getValue(it) }, { AppLanguage.set(context, it) }, equalWidth = true)
            }
        }
    }
}

@Composable
internal fun FoldersPage(onBack: () -> Unit, contentPadding: PaddingValues) {
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    val output by prefs.outputFolder.collectAsState(initial = "")
    val lightroom by prefs.lightroomFolder.collectAsState(initial = "")
    val cshot by prefs.includeCshot.collectAsState(initial = true)

    SettingsPageFrame(stringResource(R.string.settings_folders), onBack, contentPadding) {
        SettingsGroup {
            InfoRow(0, 3, KeeperIcons.Folder, stringResource(R.string.settings_output), output.trimEnd('/'))
            InfoRow(1, 3, KeeperIcons.Folder, stringResource(R.string.settings_lightroom), lightroom.trimEnd('/'))
            SwitchRow(2, 3, KeeperIcons.Camera, stringResource(R.string.settings_cshot), stringResource(R.string.settings_cshot_sub), cshot, { scope.launch { prefs.setIncludeCshot(it) } })
        }
    }
}

@Composable
internal fun ConvertPage(onBack: () -> Unit, contentPadding: PaddingValues) {
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    val quality by prefs.quality.collectAsState(initial = 94)
    val toneMapId by prefs.toneMap.collectAsState(initial = "normaal")
    val toneMap = ToneMap.of(toneMapId)
    val keepHdr by prefs.keepHdr.collectAsState(initial = false)

    val savedStrength by prefs.hdrStrength.collectAsState(initial = 100)
    // Tijdens het schuiven telt de schuif zelf; opgeslagen wordt bij loslaten.
    var strength by remember(savedStrength) { mutableIntStateOf(savedStrength) }

    SettingsPageFrame(stringResource(R.string.settings_convert), onBack, contentPadding) {
        SettingsGroup {
            SwitchRow(0, 4, KeeperIcons.Photos, stringResource(R.string.settings_keep_hdr), stringResource(R.string.settings_keep_hdr_sub), keepHdr, { scope.launch { prefs.setKeepHdr(it) } })
            ContentRow(1, 4, KeeperIcons.Image, stringResource(R.string.settings_quality)) {
                SegmentedPillRow(JpegQuality.options, quality, { "q$it" }, { scope.launch { prefs.setQuality(it) } }, equalWidth = true)
                val bpp = JpegQuality.bitsPerPixel(quality)
                Text(
                    stringResource(R.string.settings_quality_estimate, String.format(Locale.getDefault(), "%.2f", bpp), formatBytes((50_000_000L * bpp / 8).toLong())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ContentRow(2, 4, KeeperIcons.Tune, stringResource(R.string.settings_hdr_strength)) {
                HdrStrengthSlider(strength, { strength = it }, onFinished = { scope.launch { prefs.setHdrStrength(strength) } })
                Text(
                    stringResource(R.string.settings_hdr_strength_body),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ContentRow(3, 4, KeeperIcons.Tune, stringResource(R.string.settings_tonemap)) {
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
}

@Composable
internal fun RawPage(onBack: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    val lookDefault by prefs.lookDefault.collectAsState(initial = "")

    SettingsPageFrame(stringResource(R.string.settings_raw), onBack, contentPadding) {
        SettingsGroup {
            ContentRow(0, 1, KeeperIcons.OpenInNew, stringResource(R.string.settings_raw_look)) {
                val presets = remember { LookPreset.available(context) }
                val ids = listOf("") + presets.map { it.id }
                val noneLabel = stringResource(R.string.look_none)
                SegmentedPillRow(ids, lookDefault, { id -> LookPreset.of(id)?.label ?: noneLabel }, { scope.launch { prefs.setLookDefault(it) } }, equalWidth = true)
                Text(
                    stringResource(R.string.settings_raw_look_sub),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
