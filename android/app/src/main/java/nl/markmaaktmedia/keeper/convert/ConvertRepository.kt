package nl.markmaaktmedia.keeper.convert

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.data.KeeperPrefs
import nl.markmaaktmedia.keeper.scan.MediaItem
import nl.markmaaktmedia.keeper.scan.MediaScanner

sealed interface ConvertState {
    data object Idle : ConvertState
    /** [total] is 0 tot de run weet wat er te doen is. [item] is de foto die nu aan de beurt is; [pausing] als Pauzeren is gevraagd. */
    data class Running(
        val index: Int = 0, val total: Int = 0, val name: String = "", val fraction: Float = 0f,
        val item: MediaItem? = null, val how: How? = null, val alreadyDone: Int = 0, val pausing: Boolean = false, val keepsHdr: Boolean = false,
    ) : ConvertState
    data class Finished(val report: ConvertReport) : ConvertState
}

/**
 * De ene run die er tegelijk kan zijn, en wat het scherm en de melding ervan tonen. De run zelf loopt in een voorgrondservice
 * ([ConvertService]) zodat hij doorgaat als je de app verlaat; deze klasse is wat die twee verbindt.
 */
class ConvertRepository(private val context: Context, private val prefs: KeeperPrefs, scanner: MediaScanner) {
    private val job = ConvertJob(context.applicationContext, prefs, scanner)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConvertState>(ConvertState.Idle)
    val state: StateFlow<ConvertState> = _state

    private var running: Job? = null
    @Volatile private var stop = false
    private var options = ConvertOptions()

    /** Start een run, vanuit het scherm. Doet niets als er al een loopt. Met [options] voor één foto: zie [ConvertOptions]. */
    fun start(options: ConvertOptions = ConvertOptions()) {
        synchronized(this) {
            if (_state.value is ConvertState.Running) return
            this.options = options
            stop = false
            _state.value = ConvertState.Running()
        }
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ConvertService::class.java)) }
            .onFailure { _state.value = ConvertState.Finished(ConvertReport(failures = listOf(ConvertFailure("", it.message ?: "de service startte niet")))) }
    }

    /** Door de service aangeroepen zodra hij voorgrond is: laat de run lopen. */
    internal fun begin() {
        synchronized(this) {
            if (running?.isActive == true) return
            running = scope.launch {
                val report = try {
                    job.run({ p -> _state.value = ConvertState.Running(p.index, p.total, p.name, p.fraction, p.item, p.how, p.alreadyDone, stop, p.keepsHdr) }, { stop }, options)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Ook een OutOfMemoryError op een enorme foto: de run is dan afgelopen, maar de app, de melding en de wakelock blijven in de pas.
                    ConvertReport(failures = listOf(ConvertFailure("", t.message ?: t.javaClass.simpleName)))
                }
                // Onthouden voor de knop: na een pauze heet die Doorgaan, ook als de app tussendoor is afgesloten.
                runCatching { prefs.setConvertPaused(report.paused && report.left > 0) }
                _state.value = ConvertState.Finished(report)
            }
        }
    }

    /**
     * Pauzeert: stopt na de foto waar hij mee bezig is, of midden in als die nog niet af is (het halve bestand wordt dan weggehaald).
     * Wat al in de uitvoermap staat, blijft staan en telt bij de volgende keer als klaar, dus Doorgaan is gewoon weer starten.
     */
    fun requestPause() {
        stop = true
        (_state.value as? ConvertState.Running)?.let { _state.value = it.copy(pausing = true) }
    }

    /** Het verslag is gezien: terug naar rust. */
    fun acknowledge() {
        if (_state.value is ConvertState.Finished) _state.value = ConvertState.Idle
    }
}
