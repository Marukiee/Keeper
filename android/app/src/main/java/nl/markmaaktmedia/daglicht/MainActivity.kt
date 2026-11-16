package nl.markmaaktmedia.daglicht

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.markmaaktmedia.daglicht.ui.AppRoot
import nl.markmaaktmedia.daglicht.ui.theme.Appearance
import nl.markmaaktmedia.daglicht.ui.theme.DaglichtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DaglichtTheme(Appearance(pureBlack = true)) { AppRoot() } }
    }
}
