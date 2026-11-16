package nl.markmaaktmedia.keeper

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * De taal van de app. Keeper is standaard Engels, ook op een Nederlandse telefoon; de keuze staat in Instellingen en in
 * Android's eigen app-taalkeuze. AppCompatDelegate werkt hier niet, want MainActivity is geen AppCompatActivity.
 * Per-app talen bestaan vanaf Android 13; op oudere versies blijft de systeemtaal gelden.
 */
object AppLanguage {
    fun set(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    }

    /** Eén keer, bij de eerste start: Engels, tenzij Mark of Android al een taal heeft gekozen. */
    fun defaultToEnglishOnce(context: Context) {
        val flags = context.getSharedPreferences("language", Context.MODE_PRIVATE)
        if (flags.getBoolean("initialised", false)) return
        flags.edit().putBoolean("initialised", true).apply()
        if (Build.VERSION.SDK_INT >= 33 && context.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty) set(context, "en")
    }
}
