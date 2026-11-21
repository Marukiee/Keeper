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
        val hdrStrength = intPreferencesKey("hdr_strength")
        val hdrStrengthByName = stringSetPreferencesKey("hdr_strength_by_name")
        val keepHdr = booleanPreferencesKey("keep_hdr")
        val resolution = stringPreferencesKey("resolution")
        val keepHdrByName = stringSetPreferencesKey("keep_hdr_by_name")
        val viewerOptions = booleanPreferencesKey("viewer_options")
        val hideOriginals = booleanPreferencesKey("hide_originals")
        val pendingHide = stringSetPreferencesKey("pending_hide")
        val lookDefault = stringPreferencesKey("look_default")
        val themeMode = stringPreferencesKey("theme_mode")
        val seed = stringPreferencesKey("seed")
        val style = stringPreferencesKey("palette_style")
        val pureBlack = booleanPreferencesKey("pure_black")
        val convertPaused = booleanPreferencesKey("convert_paused")
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
     * Alles weer uit: opnieuw beginnen. De lijst met bewaarde foto's gaat helemaal leeg (ook namen van foto's die er niet meer zijn), en
     * de Lightroom-exports en Galerij-bewerkingen, die standaard aanstaan, komen op de uitzetlijst ([exclude]). De kruisjes van "niet
     * bewaren" blijven staan; die zeggen alleen dat je naar een foto gekeken hebt.
     */
    suspend fun clearSelection(exclude: List<String>) = edit {
        it[K.cameraVersion] = emptySet()
        it[K.skipped] = (it[K.skipped] ?: emptySet()) + exclude
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

    /** HDR-sterkte in procenten (0 tot 100): hoeveel van de HDR van de camera meetelt. Standaard 100, zoals het altijd was. */
    val hdrStrength: Flow<Int> = data.map { (it[K.hdrStrength] ?: DEFAULT_STRENGTH).coerceIn(0, 100) }.distinctUntilChanged()
    suspend fun setHdrStrength(percent: Int) = edit { it[K.hdrStrength] = percent.coerceIn(0, 100) }

    /** Sterkte per foto (bestandsnaam naar procent), voor wat afwijkt van [hdrStrength]. Staat een foto er niet in, dan geldt de standaard. */
    val hdrStrengthByName: Flow<Map<String, Int>> = data.map { StrengthEntries.decode(it[K.hdrStrengthByName]) }.distinctUntilChanged()
    /** [percent] null haalt de aanpassing weg (terug naar de standaard). */
    suspend fun setHdrStrengthFor(name: String, percent: Int?) = edit {
        val now = StrengthEntries.decode(it[K.hdrStrengthByName]).toMutableMap()
        if (percent == null) now.remove(name) else now[name] = percent.coerceIn(0, 100)
        it[K.hdrStrengthByName] = StrengthEntries.encode(now)
    }

    /**
     * HDR behouden: de uitvoer blijft een Ultra HDR-bestand (hoofdbeeld opnieuw gecodeerd, gain map mee, met de HDR-sterkte erin) in plaats van een
     * SDR-bestand met tone map. Standaard aan (Mark, 2026-11-21: "hdr toch mooier bij alles", en dan alleen comprimeren); wie de instelling ooit zelf
     * heeft gezet, houdt zijn keuze.
     */
    val keepHdr: Flow<Boolean> = data.map { it[K.keepHdr] ?: true }.distinctUntilChanged()
    suspend fun setKeepHdr(on: Boolean) = edit { it[K.keepHdr] = on }

    /** Hoeveel pixels de uitvoer krijgt: "auto" (per foto de helft als de meting daar niets zichtbaars aan verliest) of "full" (altijd alle). Zie `ResolutionCheck`. */
    val resolution: Flow<String> = data.map { it[K.resolution] ?: "auto" }.distinctUntilChanged()
    suspend fun setResolution(id: String) = edit { it[K.resolution] = id }

    /** HDR of SDR per foto (bestandsnaam naar waar of onwaar), voor wat afwijkt van [keepHdr]. Staat een foto er niet in, dan geldt de instelling. */
    val keepHdrByName: Flow<Map<String, Boolean>> = data.map { KeepEntries.decode(it[K.keepHdrByName]) }.distinctUntilChanged()
    /** [keep] null haalt de keuze weg (terug naar de instelling). */
    suspend fun setKeepHdrFor(name: String, keep: Boolean?) = edit {
        val now = KeepEntries.decode(it[K.keepHdrByName]).toMutableMap()
        if (keep == null) now.remove(name) else now[name] = keep
        it[K.keepHdrByName] = KeepEntries.encode(now)
    }

    /**
     * Het paneel met HDR of SDR en de sterkte staat onder elke HDR-foto in de viewer, ook als je doorveegt (om snel foto na foto aan te passen).
     * Standaard aan; te verbergen via het menu van de viewer.
     */
    val viewerOptions: Flow<Boolean> = data.map { it[K.viewerOptions] ?: true }.distinctUntilChanged()
    suspend fun setViewerOptions(on: Boolean) = edit { it[K.viewerOptions] = on }

    /**
     * Originelen verbergen: na een gelukte omzetting gaan de camera-JPG's waarvan de uitvoer klopt naar de prullenbak van de Galerij (zie
     * [nl.markmaaktmedia.keeper.convert.OriginalsHider]). Standaard uit; Mark zet het zelf aan.
     */
    val hideOriginals: Flow<Boolean> = data.map { it[K.hideOriginals] ?: false }.distinctUntilChanged()
    suspend fun setHideOriginals(on: Boolean) = edit { it[K.hideOriginals] = on }

    /** Originelen die verborgen mogen worden zodra de app ze gecontroleerd heeft (zie [nl.markmaaktmedia.keeper.convert.HideEntry]); overleeft het sluiten van de app. */
    val pendingHide: Flow<Set<String>> = data.map { it[K.pendingHide] ?: emptySet() }.distinctUntilChanged()
    suspend fun addPendingHide(lines: Collection<String>) = edit { it[K.pendingHide] = (it[K.pendingHide] ?: emptySet()) + lines }
    /** Haalt de regels van deze originelen (MediaStore-ids) weg: verborgen, afgekeurd of niet meer nodig. */
    suspend fun removePendingHide(originalIds: Set<Long>) = edit {
        it[K.pendingHide] = (it[K.pendingHide] ?: emptySet()).filterNot { line -> line.substringBefore('|').toLongOrNull() in originalIds }.toSet()
    }

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

    /** Of de laatste omzetting gepauzeerd is en er dus nog iets te doen staat: de knop heet dan Doorgaan. Wat klaar is, blijft klaar. */
    val convertPaused: Flow<Boolean> = data.map { it[K.convertPaused] ?: false }.distinctUntilChanged()
    suspend fun setConvertPaused(on: Boolean) = edit { it[K.convertPaused] = on }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_STRENGTH = 100
    }
}

/** De sterkte per foto als lijst van `naam|procent` (een stringset in DataStore). Een bestandsnaam bevat nooit een `|`. */
object StrengthEntries {
    fun encode(map: Map<String, Int>): Set<String> = map.map { (name, percent) -> "$name|$percent" }.toSet()

    fun decode(set: Set<String>?): Map<String, Int> = buildMap {
        set?.forEach { entry ->
            val at = entry.lastIndexOf('|')
            val percent = entry.substring(at + 1).toIntOrNull()
            if (at > 0 && percent != null) put(entry.substring(0, at), percent.coerceIn(0, 100))
        }
    }
}

/** De keuze HDR of SDR per foto als lijst van `naam|1` (HDR behouden) of `naam|0` (SDR). Een bestandsnaam bevat nooit een `|`. */
object KeepEntries {
    fun encode(map: Map<String, Boolean>): Set<String> = map.map { (name, keep) -> "$name|${if (keep) 1 else 0}" }.toSet()

    fun decode(set: Set<String>?): Map<String, Boolean> = buildMap {
        set?.forEach { entry ->
            val at = entry.lastIndexOf('|')
            val value = entry.substring(at + 1)
            if (at > 0 && (value == "1" || value == "0")) put(entry.substring(0, at), value == "1")
        }
    }
}
