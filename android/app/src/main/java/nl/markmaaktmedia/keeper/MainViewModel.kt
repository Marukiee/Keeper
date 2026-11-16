package nl.markmaaktmedia.keeper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.scan.Classifier
import nl.markmaaktmedia.keeper.scan.JpegQuality
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Snapshot
import nl.markmaaktmedia.keeper.scan.Summary

data class UiState(
    val loading: Boolean = true,
    val photos: List<Photo> = emptyList(),
    val summary: Summary = Summary(emptyMap(), 0, 0, 0),
    val sinceMs: Long = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val snapshot = MutableStateFlow<Snapshot?>(null)

    /** De keuzes van Mark liggen los van de scan: een veeg of een knop rekent alleen opnieuw, scant niet opnieuw. */
    val state: StateFlow<UiState> = combine(snapshot, graph.prefs.skipped, graph.prefs.cameraVersion, graph.prefs.quality) { snap, skipped, kept, quality ->
        if (snap == null) UiState() else {
            val photos = Classifier.classify(snap.items, snap.probes, skipped, kept, snap.doneNames, snap.lightroomFolder)
            UiState(false, photos, Classifier.summarize(photos, JpegQuality.bitsPerPixel(quality)), snap.sinceMs)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    fun refresh() {
        viewModelScope.launch { snapshot.value = graph.scanner.scan() }
    }

    /** Zet foto's aan (gaan mee) of uit. Wat niet te kiezen is, blijft zoals het is. */
    fun select(photos: List<Photo>, on: Boolean) {
        val targets = photos.filter { it.selectable }
        if (targets.isEmpty()) return
        val (exclude, keep) = targets.partition { it.defaultsOn }
        viewModelScope.launch { graph.prefs.setSelection(keep.map { it.item.name }, exclude.map { it.item.name }, on) }
    }

    fun toggle(photo: Photo) = select(listOf(photo), !photo.selected)

    fun useCameraVersion(photo: Photo, use: Boolean) {
        viewModelScope.launch { graph.prefs.setCameraVersion(photo.item.name, use) }
    }
}
