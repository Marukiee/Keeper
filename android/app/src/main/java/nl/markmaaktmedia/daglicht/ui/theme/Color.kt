package nl.markmaaktmedia.daglicht.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The palette used when the user turns wallpaper colours off, or on a phone that
 * cannot provide them.
 *
 * Built around one idea: a cool indigo that reads as "thinking", with a warm rose as
 * the only other voice in the room. Two hues and nothing else, so the accent means
 * something when it does show up. The tilted capsule in the launcher icon is drawn in
 * the same indigo, which is what ties the icon to the app it opens.
 */
object DaglichtPalette {
    val Indigo10 = Color(0xFF0B0A2B)
    val Indigo20 = Color(0xFF1B1A4A)
    val Indigo30 = Color(0xFF2E2C6B)
    val Indigo40 = Color(0xFF454391)
    val Indigo50 = Color(0xFF5B5BD6)
    val Indigo70 = Color(0xFF9E9DF0)
    val Indigo80 = Color(0xFFBEBDF8)
    val Indigo90 = Color(0xFFE2E1FF)
    val Indigo95 = Color(0xFFF1F0FF)

    val Rose10 = Color(0xFF3A0720)
    val Rose20 = Color(0xFF561433)
    val Rose30 = Color(0xFF742C4B)
    val Rose40 = Color(0xFF934464)
    val Rose80 = Color(0xFFFFB0CB)
    val Rose90 = Color(0xFFFFD9E4)

    val Neutral0 = Color(0xFF000000)
    val Neutral6 = Color(0xFF0E0E13)
    val Neutral10 = Color(0xFF15141B)
    val Neutral12 = Color(0xFF1A1922)
    val Neutral17 = Color(0xFF232230)
    val Neutral22 = Color(0xFF2C2B3A)
    val Neutral24 = Color(0xFF302F3F)
    val Neutral80 = Color(0xFFC8C5D5)
    val Neutral90 = Color(0xFFE5E1F0)
    val Neutral95 = Color(0xFFF3F0FB)
    val Neutral98 = Color(0xFFFCFAFF)
    val Neutral100 = Color(0xFFFFFFFF)

    val Error40 = Color(0xFFBA1A1A)
    val Error80 = Color(0xFFFFB4AB)
    val Error90 = Color(0xFFFFDAD6)
    val Error10 = Color(0xFF410002)

    /** Reserved for the urgent badge, so nothing else is allowed to be this loud. */
    val Urgent = Color(0xFFE8613C)
    val UrgentContainerLight = Color(0xFFFFE3DA)
    val UrgentContainerDark = Color(0xFF5A2113)
}

val DaglichtLightColors = lightColorScheme(
    primary = DaglichtPalette.Indigo50,
    onPrimary = DaglichtPalette.Neutral100,
    primaryContainer = DaglichtPalette.Indigo90,
    onPrimaryContainer = DaglichtPalette.Indigo20,
    secondary = DaglichtPalette.Indigo40,
    onSecondary = DaglichtPalette.Neutral100,
    secondaryContainer = DaglichtPalette.Indigo95,
    onSecondaryContainer = DaglichtPalette.Indigo30,
    tertiary = DaglichtPalette.Rose40,
    onTertiary = DaglichtPalette.Neutral100,
    tertiaryContainer = DaglichtPalette.Rose90,
    onTertiaryContainer = DaglichtPalette.Rose20,
    background = DaglichtPalette.Neutral98,
    onBackground = DaglichtPalette.Neutral10,
    surface = DaglichtPalette.Neutral98,
    onSurface = DaglichtPalette.Neutral10,
    surfaceVariant = DaglichtPalette.Neutral95,
    onSurfaceVariant = Color(0xFF49475A),
    surfaceContainerLowest = DaglichtPalette.Neutral100,
    surfaceContainerLow = Color(0xFFFAF7FF),
    surfaceContainer = Color(0xFFF4F1FC),
    surfaceContainerHigh = Color(0xFFEEEBF7),
    surfaceContainerHighest = Color(0xFFE8E5F2),
    outline = Color(0xFF7B7890),
    outlineVariant = Color(0xFFCBC7DA),
    error = DaglichtPalette.Error40,
    onError = DaglichtPalette.Neutral100,
    errorContainer = DaglichtPalette.Error90,
    onErrorContainer = DaglichtPalette.Error10,
    inverseSurface = DaglichtPalette.Neutral17,
    inverseOnSurface = DaglichtPalette.Neutral95,
    inversePrimary = DaglichtPalette.Indigo80,
)

val DaglichtDarkColors = darkColorScheme(
    primary = DaglichtPalette.Indigo80,
    onPrimary = DaglichtPalette.Indigo20,
    primaryContainer = DaglichtPalette.Indigo30,
    onPrimaryContainer = DaglichtPalette.Indigo90,
    secondary = DaglichtPalette.Indigo70,
    onSecondary = DaglichtPalette.Indigo20,
    secondaryContainer = DaglichtPalette.Indigo30,
    onSecondaryContainer = DaglichtPalette.Indigo90,
    tertiary = DaglichtPalette.Rose80,
    onTertiary = DaglichtPalette.Rose20,
    tertiaryContainer = DaglichtPalette.Rose30,
    onTertiaryContainer = DaglichtPalette.Rose90,
    background = DaglichtPalette.Neutral10,
    onBackground = DaglichtPalette.Neutral90,
    surface = DaglichtPalette.Neutral10,
    onSurface = DaglichtPalette.Neutral90,
    surfaceVariant = DaglichtPalette.Neutral22,
    onSurfaceVariant = DaglichtPalette.Neutral80,
    surfaceContainerLowest = DaglichtPalette.Neutral6,
    surfaceContainerLow = DaglichtPalette.Neutral12,
    surfaceContainer = DaglichtPalette.Neutral17,
    surfaceContainerHigh = DaglichtPalette.Neutral22,
    surfaceContainerHighest = DaglichtPalette.Neutral24,
    outline = Color(0xFF908DA3),
    outlineVariant = Color(0xFF49475A),
    error = DaglichtPalette.Error80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = DaglichtPalette.Error90,
    inverseSurface = DaglichtPalette.Neutral90,
    inverseOnSurface = DaglichtPalette.Neutral17,
    inversePrimary = DaglichtPalette.Indigo50,
)
