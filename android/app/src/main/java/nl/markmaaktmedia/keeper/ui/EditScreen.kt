package nl.markmaaktmedia.keeper.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.lightroom.LookLauncher
import nl.markmaaktmedia.keeper.lightroom.LookPreset
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.components.EmptyState
import nl.markmaaktmedia.keeper.ui.components.GroupedRow
import nl.markmaaktmedia.keeper.ui.components.KeeperIconButton
import nl.markmaaktmedia.keeper.ui.components.PillBadge
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.components.bouncyClickable
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.PillShape

/** RAW's die nog een bewerking nodig hebben, per dag gegroepeerd, met per foto de Lightroom-preset en twee acties. */
@Composable
fun EditScreen(photos: List<Photo>, onCameraVersion: (Photo, Boolean) -> Unit, onTrash: (Photo) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember(photos) { photos.filter { it.status == Status.NeedsEdit || it.status == Status.CameraVersion }.sortedByDescending { it.takenMs } }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(list) { list.groupBy { Instant.ofEpochMilli(it.takenMs).atZone(zone).toLocalDate() } }
    val today = remember { LocalDate.now(zone) }
    val todayLabel = stringResource(R.string.day_today)
    val yesterdayLabel = stringResource(R.string.day_yesterday)

    val defaultId by context.graph.prefs.lookDefault.collectAsState(initial = "")
    val presets = remember { LookPreset.available(context) }
    // Per RAW de preset van de camera-JPG die er kort naast is genomen (een DNG zelf zegt het niet); Mark kan hem per foto aanpassen.
    val guesses by produceState(emptyMap<Long, LookLauncher.Guess>(), list.map { it.item.id }) {
        value = list.filter { it.item.isDng }.associate { it.item.id to LookLauncher.guess(context, it, photos) }
    }
    val picked = remember { mutableStateMapOf<Long, String>() }
    fun effective(photo: Photo): LookPreset? {
        picked[photo.item.id]?.let { return LookPreset.of(it) }
        val g = guesses[photo.item.id] ?: return null
        return if (g.oppoName != null) g.preset else LookPreset.of(defaultId)
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            ScreenHeader(
                stringResource(R.string.edit_title),
                if (list.isEmpty()) stringResource(R.string.edit_sub) else pluralStringResource(R.plurals.edit_sub_count, list.size, list.size),
            )
        }
        if (list.isEmpty()) item {
            EmptyState(stringResource(R.string.edit_empty_title), stringResource(R.string.edit_empty_body), icon = KeeperIcons.CheckCircle)
        }
        days.forEach { (day, dayPhotos) ->
            item(key = "day-$day") {
                Text(
                    dayLabel(day, today, todayLabel, yesterdayLabel),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                )
            }
            itemsIndexed(dayPhotos, key = { _, p -> p.item.id }) { index, photo ->
                val chosen = photo.status == Status.CameraVersion
                val guess = guesses[photo.item.id]
                val missing = if (picked.containsKey(photo.item.id)) null else guess?.missing
                GroupedRow(index, dayPhotos.size) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // IntrinsicSize.Min: de tekstkolom is precies zo hoog als de foto, dus er blijft geen lege ruimte onder de presetknop.
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PhotoThumb(photo.item, 320, Modifier.size(88.dp).clip(MaterialTheme.shapes.medium))
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "${formatTime(photo.takenMs)}  ·  ${formatMegapixels(photo.item.megapixels)}",
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        photo.item.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (chosen) PillBadge(stringResource(R.string.edit_camera_chosen), icon = KeeperIcons.Check)
                                else if (photo.item.isDng && presets.isNotEmpty()) {
                                    PresetPicker(effective(photo), presets, missing) { picked[photo.item.id] = it }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Het gewicht volgt de lengte van de teksten, anders wordt "Lightroom" naast de prullenbak afgekapt.
                            PrimaryPillButton(
                                stringResource(R.string.edit_open), { scope.launch { LookLauncher.open(context, photo, effective(photo)) } },
                                Modifier.weight(1.12f), icon = KeeperIcons.OpenInNew,
                            )
                            if (chosen) SecondaryPillButton(stringResource(R.string.edit_camera_undo), { onCameraVersion(photo, false) }, Modifier.weight(1f))
                            else SecondaryPillButton(stringResource(R.string.edit_camera), { onCameraVersion(photo, true) }, Modifier.weight(1f), icon = KeeperIcons.Camera)
                            KeeperIconButton(KeeperIcons.Delete, stringResource(R.string.review_trash), { onTrash(photo) }, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** De preset van deze RAW als keuzeknop: tik en kies een andere, of geen. [missing] is een preset die de camera noemde maar waar Keeper nog geen look voor heeft. */
@Composable
private fun PresetPicker(selected: LookPreset?, presets: List<LookPreset>, missing: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = selected != null && missing == null
    val label = when {
        missing != null -> stringResource(R.string.edit_preset_missing, missing)
        selected != null -> selected.label
        else -> stringResource(R.string.edit_preset_none)
    }
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(PillShape).background(container).bouncyClickable(onClick = { open = true }).padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(KeeperIcons.Tune, null, tint = ink, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(KeeperIcons.ChevronDown, null, tint = ink, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_preset_none)) }, onClick = { open = false; onPick("") },
                trailingIcon = { if (selected == null && missing == null) Icon(KeeperIcons.Check, null, Modifier.size(18.dp)) },
            )
            presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) }, onClick = { open = false; onPick(p.id) },
                    trailingIcon = { if (p == selected && missing == null) Icon(KeeperIcons.Check, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** Opent de RAW in Lightroom met de preset die bij de foto past (JPG ernaast) of de standaardpreset uit de instellingen. */
internal fun openInLightroom(scope: CoroutineScope, context: Context, photo: Photo, all: List<Photo>, defaultId: String) {
    scope.launch {
        val g = LookLauncher.guess(context, photo, all)
        LookLauncher.open(context, photo, if (g.oppoName != null) g.preset else LookPreset.of(defaultId))
    }
}
