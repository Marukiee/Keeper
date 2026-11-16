package nl.markmaaktmedia.keeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.markmaaktmedia.keeper.ui.AppRoot
import nl.markmaaktmedia.keeper.ui.theme.Appearance
import nl.markmaaktmedia.keeper.ui.theme.KeeperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { KeeperTheme(Appearance(pureBlack = true)) { AppRoot() } }
    }
}
