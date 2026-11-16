package nl.markmaaktmedia.keeper.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.ui.appearance.LiveScreenPreview
import nl.markmaaktmedia.keeper.ui.appearance.SeedPreviewTile
import nl.markmaaktmedia.keeper.ui.appearance.StylePreviewCard
import nl.markmaaktmedia.keeper.ui.components.ContentRow
import nl.markmaaktmedia.keeper.ui.components.SectionHeader
import nl.markmaaktmedia.keeper.ui.components.SettingsGroup
import nl.markmaaktmedia.keeper.ui.theme.ColourSeed
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.KeeperMotion
import nl.markmaaktmedia.keeper.ui.theme.LocalAppearance
import nl.markmaaktmedia.keeper.ui.theme.PaletteStyle
import nl.markmaaktmedia.keeper.ui.theme.isDark
import nl.markmaaktmedia.keeper.ui.theme.resolve

/**
 * Accent en paletstijl, zoals in Tandem: elke keuze is getekend in het palet dat hij zou geven, en boven de keuzes staat een
 * klein Keeper-scherm in de kleuren die de app nu draagt.
 */
@Composable
internal fun AppearancePage(onBack: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val scope = rememberCoroutineScope()
    // Uit het thema gelezen en niet opnieuw verzameld: waar de previews mee vergeleken worden, is wat het thema nu draagt.
    val appearance = LocalAppearance.current
    val dark = appearance.isDark()

    SettingsPageFrame(stringResource(R.string.settings_appearance), onBack, contentPadding) {
        Spacer(Modifier.height(6.dp))
        LiveScreenPreview()

        SectionHeader(stringResource(R.string.appearance_accent))
        SettingsGroup {
            ContentRow(0, 2, KeeperIcons.Palette, stringResource(R.string.appearance_accent)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Achtergrond is een van de tegels en geen schakelaar erboven: het is een keuze uit hetzelfde rijtje.
                    ColourSeed.entries.chunked(SEED_COLUMNS).forEach { rowSeeds ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowSeeds.forEach { seed ->
                                SeedPreviewTile(
                                    seed = seed.resolve(context),
                                    style = appearance.style,
                                    dark = dark,
                                    pureBlack = appearance.pureBlack,
                                    selected = appearance.seed == seed,
                                    label = stringResource(seedLabel(seed)),
                                    onClick = { scope.launch { prefs.setSeed(seed) } },
                                    modifier = Modifier.weight(1f),
                                    overlay = { scheme ->
                                        if (seed == ColourSeed.WALLPAPER) {
                                            Box(
                                                Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).clip(CircleShape).background(scheme.surface.copy(alpha = 0.9f)),
                                                contentAlignment = Alignment.Center,
                                            ) { Icon(KeeperIcons.Image, null, tint = scheme.onSurface, modifier = Modifier.size(12.dp)) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Note(visible = appearance.seed == ColourSeed.WALLPAPER, text = stringResource(R.string.colours_wallpaper_note))
                    Note(visible = appearance.style == PaletteStyle.MONOCHROME, text = stringResource(R.string.colours_mono_note))
                }
            }
            ContentRow(1, 2, KeeperIcons.Palette, stringResource(R.string.appearance_style)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val seed = appearance.seed.resolve(context)
                    PaletteStyle.entries.chunked(STYLE_COLUMNS).forEach { rowStyles ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowStyles.forEach { style ->
                                StylePreviewCard(
                                    style = style,
                                    seed = seed,
                                    dark = dark,
                                    pureBlack = appearance.pureBlack,
                                    selected = appearance.style == style,
                                    label = stringResource(styleLabel(style)),
                                    onClick = { scope.launch { prefs.setStyle(style) } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = appearance.style,
                        transitionSpec = { fadeIn(KeeperMotion.fadeSpec()) togetherWith fadeOut(KeeperMotion.fadeSpec()) },
                        label = "styleDescription",
                    ) { style ->
                        Text(
                            text = stringResource(styleDescription(style)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Een regel uitleg die inklapt zodra hij niet meer waar is. */
@Composable
private fun Note(visible: Boolean, text: String) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(KeeperMotion.sizeSpring()) + fadeIn(KeeperMotion.fadeSpec()),
        exit = shrinkVertically(KeeperMotion.sizeSpring()) + fadeOut(KeeperMotion.fadeSpec()),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

private const val SEED_COLUMNS = 4
private const val STYLE_COLUMNS = 3

@StringRes
internal fun seedLabel(seed: ColourSeed): Int = when (seed) {
    ColourSeed.WALLPAPER -> R.string.colours_seed_wallpaper
    ColourSeed.INDIGO -> R.string.colours_seed_indigo
    ColourSeed.OCEAN -> R.string.colours_seed_ocean
    ColourSeed.FOREST -> R.string.colours_seed_forest
    ColourSeed.AMBER -> R.string.colours_seed_amber
    ColourSeed.ROSE -> R.string.colours_seed_rose
    ColourSeed.PLUM -> R.string.colours_seed_plum
    ColourSeed.SLATE -> R.string.colours_seed_slate
}

@StringRes
private fun styleLabel(style: PaletteStyle): Int = when (style) {
    PaletteStyle.TONAL_SPOT -> R.string.colours_style_tonal_spot
    PaletteStyle.VIBRANT -> R.string.colours_style_vibrant
    PaletteStyle.EXPRESSIVE -> R.string.colours_style_expressive
    PaletteStyle.FRUIT_SALAD -> R.string.colours_style_fruit_salad
    PaletteStyle.FIDELITY -> R.string.colours_style_fidelity
    PaletteStyle.MONOCHROME -> R.string.colours_style_monochrome
}

@StringRes
private fun styleDescription(style: PaletteStyle): Int = when (style) {
    PaletteStyle.TONAL_SPOT -> R.string.colours_style_tonal_spot_desc
    PaletteStyle.VIBRANT -> R.string.colours_style_vibrant_desc
    PaletteStyle.EXPRESSIVE -> R.string.colours_style_expressive_desc
    PaletteStyle.FRUIT_SALAD -> R.string.colours_style_fruit_salad_desc
    PaletteStyle.FIDELITY -> R.string.colours_style_fidelity_desc
    PaletteStyle.MONOCHROME -> R.string.colours_style_monochrome_desc
}
