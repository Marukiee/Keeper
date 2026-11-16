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
import nl.markmaaktmedia.keeper.scan.MediaScanner

sealed interface ConvertState {
    data object Idle : ConvertState
    /** [total] is 0 tot de run weet wat er te doen is. */
    data class Running(val index: Int = 0, val total: Int = 0, val name: String = "", val fraction: Float = 0f, val stopping: Boolean = false) : ConvertState
    data class Finished(val report: ConvertReport) : ConvertState
}

/**
 * De ene run die er tegelijk kan zijn, en wat het scherm en de melding ervan tonen. De run zelf loopt in een voorgrondservice
 * ([ConvertService]) zodat hij doorgaat als je de app verlaat; deze klasse is wat die twee verbindt.
 */
class ConvertRepository(private val context: Context, prefs: KeeperPrefs, scanner: MediaScanner) {
    private val job = ConvertJob(context.applicationContext, prefs, scanner)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConvertState>(ConvertState.Idle)
    val state: StateFlow<ConvertState> = _state

    private var running: Job? = null
    @Volatile private var stop = false

    /** Start een run, vanuit het scherm. Doet niets als er al een loopt. */
    fun start() {
        synchronized(this) {
            if (_state.value is ConvertState.Running) return
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
                    job.run({ p -> _state.value = ConvertState.Running(p.index, p.total, p.name, p.fraction, stop) }, { stop })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ConvertReport(failures = listOf(ConvertFailure("", e.message ?: e.javaClass.simpleName)))
                }
                _state.value = ConvertState.Finished(report)
            }
        }
    }

    /** Stopt na de foto waar hij mee bezig is, of midden in als die nog niet af is (het halve bestand wordt dan weggehaald). */
    fun requestStop() {
        stop = true
        (_state.value as? ConvertState.Running)?.let { _state.value = it.copy(stopping = true) }
    }

    /** Het verslag is gezien: terug naar rust. */
    fun acknowledge() {
        if (_state.value is ConvertState.Finished) _state.value = ConvertState.Idle
    }
}
