package nl.markmaaktmedia.keeper.ui.theme

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
object KeeperPalette {
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

val KeeperLightColors = lightColorScheme(
    primary = KeeperPalette.Indigo50,
    onPrimary = KeeperPalette.Neutral100,
    primaryContainer = KeeperPalette.Indigo90,
    onPrimaryContainer = KeeperPalette.Indigo20,
    secondary = KeeperPalette.Indigo40,
    onSecondary = KeeperPalette.Neutral100,
    secondaryContainer = KeeperPalette.Indigo95,
    onSecondaryContainer = KeeperPalette.Indigo30,
    tertiary = KeeperPalette.Rose40,
    onTertiary = KeeperPalette.Neutral100,
    tertiaryContainer = KeeperPalette.Rose90,
    onTertiaryContainer = KeeperPalette.Rose20,
    background = KeeperPalette.Neutral98,
    onBackground = KeeperPalette.Neutral10,
    surface = KeeperPalette.Neutral98,
    onSurface = KeeperPalette.Neutral10,
    surfaceVariant = KeeperPalette.Neutral95,
    onSurfaceVariant = Color(0xFF49475A),
    surfaceContainerLowest = KeeperPalette.Neutral100,
    surfaceContainerLow = Color(0xFFFAF7FF),
    surfaceContainer = Color(0xFFF4F1FC),
    surfaceContainerHigh = Color(0xFFEEEBF7),
    surfaceContainerHighest = Color(0xFFE8E5F2),
    outline = Color(0xFF7B7890),
    outlineVariant = Color(0xFFCBC7DA),
    error = KeeperPalette.Error40,
    onError = KeeperPalette.Neutral100,
    errorContainer = KeeperPalette.Error90,
    onErrorContainer = KeeperPalette.Error10,
    inverseSurface = KeeperPalette.Neutral17,
    inverseOnSurface = KeeperPalette.Neutral95,
    inversePrimary = KeeperPalette.Indigo80,
)

val KeeperDarkColors = darkColorScheme(
    primary = KeeperPalette.Indigo80,
    onPrimary = KeeperPalette.Indigo20,
    primaryContainer = KeeperPalette.Indigo30,
    onPrimaryContainer = KeeperPalette.Indigo90,
    secondary = KeeperPalette.Indigo70,
    onSecondary = KeeperPalette.Indigo20,
    secondaryContainer = KeeperPalette.Indigo30,
    onSecondaryContainer = KeeperPalette.Indigo90,
    tertiary = KeeperPalette.Rose80,
    onTertiary = KeeperPalette.Rose20,
    tertiaryContainer = KeeperPalette.Rose30,
    onTertiaryContainer = KeeperPalette.Rose90,
    background = KeeperPalette.Neutral10,
    onBackground = KeeperPalette.Neutral90,
    surface = KeeperPalette.Neutral10,
    onSurface = KeeperPalette.Neutral90,
    surfaceVariant = KeeperPalette.Neutral22,
    onSurfaceVariant = KeeperPalette.Neutral80,
    surfaceContainerLowest = KeeperPalette.Neutral6,
    surfaceContainerLow = KeeperPalette.Neutral12,
    surfaceContainer = KeeperPalette.Neutral17,
    surfaceContainerHigh = KeeperPalette.Neutral22,
    surfaceContainerHighest = KeeperPalette.Neutral24,
    outline = Color(0xFF908DA3),
    outlineVariant = Color(0xFF49475A),
    error = KeeperPalette.Error80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = KeeperPalette.Error90,
    inverseSurface = KeeperPalette.Neutral90,
    inverseOnSurface = KeeperPalette.Neutral17,
    inversePrimary = KeeperPalette.Indigo50,
)
