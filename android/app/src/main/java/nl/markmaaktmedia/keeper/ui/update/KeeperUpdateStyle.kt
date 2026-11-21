package nl.markmaaktmedia.keeper.ui.update

import androidx.compose.runtime.Composable
import nl.markmaaktmedia.keeper.ui.theme.LocalKeeperExtraColors
import nl.markmaaktmedia.markupdate.ui.UpdateCardStyle

/**
 * Wat in Keeper anders is aan de gedeelde updatekaart (module markupdate): het groen van het vinkje, hetzelfde als dat van een
 * laadbatterij elders in de app. Vorm en beweging zijn al die van Keeper, dat zijn de standaardwaarden van de kaart.
 */
@Composable
fun keeperUpdateStyle() = UpdateCardStyle(successColor = LocalKeeperExtraColors.current.charging)
