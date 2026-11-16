package nl.markmaaktmedia.keeper.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.lightroom.LookLauncher
import nl.markmaaktmedia.keeper.lightroom.LookPreset
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.components.KeeperIconButton
import nl.markmaaktmedia.keeper.ui.components.EmptyState
import nl.markmaaktmedia.keeper.ui.components.GroupedRow
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons

@Composable
fun EditScreen(photos: List<Photo>, onCameraVersion: (Photo, Boolean) -> Unit, onTrash: (Photo) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val list = photos.filter { it.status == Status.NeedsEdit || it.status == Status.CameraVersion }
    val scope = rememberCoroutineScope()
    val defaultId by context.graph.prefs.lookDefault.collectAsState(initial = "")
    val presets = remember { LookPreset.available(context) }
    // Per RAW de preset van de camera-JPG die er kort naast is genomen (een DNG zelf zegt het niet); Mark kan hem per foto aanpassen.
    val guesses by produceState(emptyMap<Long, LookPreset?>(), list.map { it.item.id }) {
        value = list.filter { it.item.isDng }.associate { it.item.id to LookLauncher.guess(context, it, photos) }
    }
    val picked = remember { mutableStateMapOf<Long, String>() }
    fun effective(photo: Photo): LookPreset? =
        picked[photo.item.id]?.let { LookPreset.of(it) } ?: if (picked.containsKey(photo.item.id)) null else guesses[photo.item.id] ?: LookPreset.of(defaultId)
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(stringResource(R.string.edit_title), stringResource(R.string.edit_sub)) }
        if (list.isEmpty()) item {
            EmptyState(stringResource(R.string.edit_empty_title), stringResource(R.string.edit_empty_body), icon = KeeperIcons.CheckCircle)
        }
        items(list, key = { it.item.id }) { photo ->
            val chosen = photo.status == Status.CameraVersion
            GroupedRow(0, 1) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhotoThumb(photo.item, 240, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(photo.item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${formatMegapixels(photo.item.megapixels)}  ·  ${formatTime(photo.item.dateTakenMs.takeIf { it > 0 } ?: photo.item.dateAddedMs)}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (chosen) Text(stringResource(R.string.edit_camera_chosen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        KeeperIconButton(KeeperIcons.Delete, stringResource(R.string.review_trash), { onTrash(photo) }, tint = MaterialTheme.colorScheme.error)
                    }
                    if (photo.item.isDng && presets.isNotEmpty()) PresetPicker(effective(photo), presets) { picked[photo.item.id] = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryPillButton(stringResource(R.string.edit_open), { scope.launch { LookLauncher.open(context, photo, effective(photo)) } }, Modifier.weight(1f), icon = KeeperIcons.OpenInNew)
                        if (chosen) SecondaryPillButton(stringResource(R.string.edit_camera_undo), { onCameraVersion(photo, false) }, Modifier.weight(1f))
                        else PrimaryPillButton(stringResource(R.string.edit_camera), { onCameraVersion(photo, true) }, Modifier.weight(1f), icon = KeeperIcons.Camera)
                    }
                }
            }
        }
    }
}

/** Kiest de Lightroom-preset voor één RAW. "" betekent: geen preset. */
@Composable
private fun PresetPicker(selected: LookPreset?, presets: List<LookPreset>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.edit_preset, selected?.label ?: stringResource(R.string.look_none)))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.look_none)) }, onClick = { open = false; onPick("") })
            presets.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; onPick(p.id) }) }
        }
    }
}

/** Opent de RAW in Lightroom met de preset die bij de foto past (JPG ernaast) of de standaardpreset uit de instellingen. */
internal fun openInLightroom(scope: CoroutineScope, context: Context, photo: Photo, all: List<Photo>, defaultId: String) {
    scope.launch { LookLauncher.open(context, photo, LookLauncher.guess(context, photo, all) ?: LookPreset.of(defaultId)) }
}
