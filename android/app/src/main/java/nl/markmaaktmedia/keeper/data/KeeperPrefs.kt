package nl.markmaaktmedia.keeper.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.markmaaktmedia.keeper.ui.theme.Appearance
import nl.markmaaktmedia.keeper.ui.theme.ColourSeed
import nl.markmaaktmedia.keeper.ui.theme.PaletteStyle
import nl.markmaaktmedia.keeper.ui.theme.ThemeMode

private val Context.store by preferencesDataStore("keeper")

/** Alle instellingen en keuzes van Mark. Niets hiervan wijzigt ooit een foto. */
class KeeperPrefs(private val context: Context) {
    private val data get() = context.store.data
    /** Zonder ingestelde datum: zeven dagen terug vanaf het opstarten. Eén vaste waarde per start, zodat een scan niet elke keer een andere vraag stelt. */
    private val defaultSince = System.currentTimeMillis() - 7 * DAY_MS

    private object K {
        val autoUpdate = booleanPreferencesKey("auto_update_check")
        val lastCheck = longPreferencesKey("last_update_check")
        val dismissed = stringPreferencesKey("dismissed_update")
        val skipped = stringSetPreferencesKey("skipped")
        val cameraVersion = stringSetPreferencesKey("camera_version")
        val rejected = stringSetPreferencesKey("rejected")
        val since = longPreferencesKey("since_ms")
        val outputFolder = stringPreferencesKey("output_folder")
        val lightroomFolder = stringPreferencesKey("lightroom_folder")
        val includeCshot = booleanPreferencesKey("include_cshot")
        val quality = intPreferencesKey("jpeg_quality")
        val toneMap = stringPreferencesKey("tone_map")
        val lookDefault = stringPreferencesKey("look_default")
        val themeMode = stringPreferencesKey("theme_mode")
        val seed = stringPreferencesKey("seed")
        val style = stringPreferencesKey("palette_style")
        val pureBlack = booleanPreferencesKey("pure_black")
    }

    val autoUpdateCheck: Flow<Boolean> = data.map { it[K.autoUpdate] ?: true }
    suspend fun setAutoUpdateCheck(on: Boolean) = edit { it[K.autoUpdate] = on }
    val lastUpdateCheck: Flow<Long> = data.map { it[K.lastCheck] ?: 0L }
    suspend fun setLastUpdateCheck(ms: Long) = edit { it[K.lastCheck] = ms }
    val dismissedUpdate: Flow<String?> = data.map { it[K.dismissed] }
    suspend fun setDismissedUpdate(tag: String) = edit { it[K.dismissed] = tag }

    /** Bestandsnamen die Mark heeft overgeslagen (naar links geveegd). De naam is uniek in DCIM/Camera. */
    val skipped: Flow<Set<String>> = data.map { it[K.skipped] ?: emptySet() }.distinctUntilChanged()
    suspend fun setSkipped(name: String, skip: Boolean) = edit {
        val now = it[K.skipped] ?: emptySet()
        it[K.skipped] = if (skip) now + name else now - name
    }

    /** Bestandsnamen die Mark bewaart (JPG) of waarvan hij de camera-versie van de RAW wil gebruiken (IFD0 van de DNG). */
    val cameraVersion: Flow<Set<String>> = data.map { it[K.cameraVersion] ?: emptySet() }.distinctUntilChanged()
    suspend fun setCameraVersion(name: String, use: Boolean) = edit {
        val now = it[K.cameraVersion] ?: emptySet()
        it[K.cameraVersion] = if (use) now + name else now - name
    }

    /** Alles wat later is toegevoegd telt als nieuw. Standaard zeven dagen terug, na een gelukte run het eindmoment. */
    val sinceMs: Flow<Long> = data.map { it[K.since] ?: defaultSince }
    suspend fun setSinceMs(ms: Long) = edit { it[K.since] = ms }

    val outputFolder: Flow<String> = data.map { it[K.outputFolder] ?: "Pictures/Keeper/" }
    suspend fun setOutputFolder(path: String) = edit { it[K.outputFolder] = path }
    val lightroomFolder: Flow<String> = data.map { it[K.lightroomFolder] ?: "Pictures/AdobeLightroom/" }
    val includeCshot: Flow<Boolean> = data.map { it[K.includeCshot] ?: true }
    suspend fun setIncludeCshot(on: Boolean) = edit { it[K.includeCshot] = on }

    /**
     * Zet foto's aan of uit in één keer. [keep] zijn camera-foto's (JPG en RAW): aan is bewaren. [exclude] zijn Lightroom-exports en
     * Galerij-bewerkingen: die staan standaard aan, dus uit is op de uitzetlijst komen.
     */
    suspend fun setSelection(keep: List<String>, exclude: List<String>, on: Boolean) = edit {
        val kept = it[K.cameraVersion] ?: emptySet()
        val skipped = it[K.skipped] ?: emptySet()
        // Wie weer bewaard wordt, is niet langer weggedaan.
        if (on) it[K.rejected] = (it[K.rejected] ?: emptySet()) - keep.toSet() - exclude.toSet()
        it[K.cameraVersion] = if (on) kept + keep else kept - keep.toSet()
        it[K.skipped] = if (on) skipped - exclude.toSet() else skipped + exclude
    }

    /**
     * Bewust niet bewaren (de kruisknop in Bekijken): gaat niet mee en krijgt een kruisje, om te zien dat je er al naar gekeken hebt.
     * Het verschil met gewoon uitgezet is alleen het kruisje; Opruimen verandert er niet van.
     */
    val rejected: Flow<Set<String>> = data.map { it[K.rejected] ?: emptySet() }.distinctUntilChanged()
    /**
     * Het kruisje weer weghalen (nog een keer op "niet bewaren" tikken). Wat standaard aanstaat (Lightroom-export, Galerij-bewerking) gaat
     * dan ook weer aan; de rest blijft niet bewaard, maar zonder kruisje.
     */
    suspend fun undoReject(keep: List<String>, exclude: List<String>) = edit {
        it[K.rejected] = (it[K.rejected] ?: emptySet()) - keep.toSet() - exclude.toSet()
        it[K.skipped] = (it[K.skipped] ?: emptySet()) - exclude.toSet()
    }

    suspend fun reject(keep: List<String>, exclude: List<String>) = edit {
        it[K.cameraVersion] = (it[K.cameraVersion] ?: emptySet()) - keep.toSet()
        it[K.skipped] = (it[K.skipped] ?: emptySet()) + exclude
        it[K.rejected] = (it[K.rejected] ?: emptySet()) + keep + exclude
    }

    /** JPEG-kwaliteit voor het omzetten (jpegli). Fase 0: q94 zit tussen q93 en q95 in. */
    val quality: Flow<Int> = data.map { it[K.quality] ?: 94 }.distinctUntilChanged()
    suspend fun setQuality(q: Int) = edit { it[K.quality] = q }

    /** Naam van de tone map (zie [nl.markmaaktmedia.keeper.scan.ToneMap]). */
    val toneMap: Flow<String> = data.map { it[K.toneMap] ?: "normaal" }
    suspend fun setToneMap(name: String) = edit { it[K.toneMap] = name }

    /** Preset die Lightroom over een RAW krijgt als de camera-JPG ernaast niet zegt welke het was ("" is geen). Zie `LookPreset`. */
    val lookDefault: Flow<String> = data.map { it[K.lookDefault] ?: "" }
    suspend fun setLookDefault(id: String) = edit { it[K.lookDefault] = id }

    /** Hoe de app eruitziet. Puur zwart (voor OLED) staat standaard uit: donker is dan het gewone donkergrijs van Material. */
    val appearance: Flow<Appearance> = data.map { p ->
        Appearance(
            mode = p[K.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            seed = ColourSeed.fromKey(p[K.seed]),
            style = PaletteStyle.fromKey(p[K.style]),
            pureBlack = p[K.pureBlack] ?: false,
        )
    }.distinctUntilChanged()
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[K.themeMode] = mode.name }
    suspend fun setSeed(seed: ColourSeed) = edit { it[K.seed] = seed.storageKey }
    suspend fun setStyle(style: PaletteStyle) = edit { it[K.style] = style.storageKey }
    suspend fun setPureBlack(on: Boolean) = edit { it[K.pureBlack] = on }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    private companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}
