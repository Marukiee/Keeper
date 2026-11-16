package nl.markmaaktmedia.daglicht.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.daglicht.R
import nl.markmaaktmedia.daglicht.UiState
import nl.markmaaktmedia.daglicht.scan.Photo
import nl.markmaaktmedia.daglicht.scan.Status
import nl.markmaaktmedia.daglicht.ui.components.DaglichtDialog
import nl.markmaaktmedia.daglicht.ui.components.DaglichtIconButton
import nl.markmaaktmedia.daglicht.ui.components.EmptyState
import nl.markmaaktmedia.daglicht.ui.components.SecondaryPillButton
import nl.markmaaktmedia.daglicht.ui.components.PillSpinner
import nl.markmaaktmedia.daglicht.ui.components.PrimaryPillButton
import nl.markmaaktmedia.daglicht.ui.components.SegmentedPillRow
import nl.markmaaktmedia.daglicht.ui.components.bouncyClickable
import nl.markmaaktmedia.daglicht.ui.theme.DaglichtIcons
import nl.markmaaktmedia.daglicht.ui.theme.DaglichtMotion
import nl.markmaaktmedia.daglicht.ui.theme.PillShape
import nl.markmaaktmedia.daglicht.ui.update.UpdateBanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class GalleryFilter(@StringRes val label: Int) {
    All(R.string.filter_all), Favorites(R.string.filter_favorites), Selected(R.string.filter_selected), Raw(R.string.filter_raw);

    fun matches(p: Photo) = when (this) {
        All -> true
        Favorites -> p.item.isFavorite
        Selected -> p.selected
        Raw -> p.item.isDng
    }
}

private sealed interface Entry {
    data class Header(val day: LocalDate, val photos: List<Photo>) : Entry
    data class Cell(val photo: Photo) : Entry
}

private val zone: ZoneId get() = ZoneId.systemDefault()
private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl", "NL"))

/**
 * De startpagina: een galerij van wat er nieuw is. Tik op een foto om hem groot te bekijken en in te zoomen, houd ingedrukt
 * en sleep om er meer tegelijk aan of uit te zetten, tik op het rondje voor één foto. Aan betekent: gaat mee.
 */
@Composable
fun GalleryScreen(
    state: UiState,
    filter: GalleryFilter,
    onFilter: (GalleryFilter) -> Unit,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSelect: (List<Photo>, Boolean) -> Unit,
    onOpen: (Photo, List<Photo>) -> Unit,
    onOverview: () -> Unit,
    onTrashMany: (List<Photo>) -> Unit,
) {
    // Opruimen: welke dag en welke foto's van die dag. Pas na een bevestiging gaat er iets naar de prullenbak.
    var cleanup by remember { mutableStateOf<Pair<String, List<Photo>>?>(null) }
    // Alles uitzetten kan per ongeluk; een paar seconden lang kun je het ongedaan maken.
    var cleared by remember { mutableStateOf<List<Photo>>(emptyList()) }
    LaunchedEffect(cleared) { if (cleared.isNotEmpty()) { delay(6000); cleared = emptyList() } }
    val shown = remember(state.photos, filter) { state.photos.filter(filter::matches) }
    val today = remember { LocalDate.now(zone) }
    val todayLabel = stringResource(R.string.day_today)
    val yesterdayLabel = stringResource(R.string.day_yesterday)
    val entries = remember(shown) {
        buildList {
            shown.groupBy { Instant.ofEpochMilli(it.takenMs).atZone(zone).toLocalDate() }.forEach { (day, photos) ->
                add(Entry.Header(day, photos)); photos.forEach { add(Entry.Cell(it)) }
            }
        }
    }
    val labels = GalleryFilter.entries.associateWith { stringResource(it.label) }
    cleanup?.let { (day, photos) ->
        CleanupDialog(day, photos, { remove -> cleanup = null; onTrashMany(remove) }, { cleanup = null })
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            UpdateBanner(Modifier.padding(top = 8.dp))
            ScreenHeader(
                stringResource(R.string.gallery_title),
                if (hasPermission && state.sinceMs > 0) stringResource(R.string.gallery_sub, state.photos.size, formatDay(state.sinceMs)) else null,
            )
            if (hasPermission && state.photos.isNotEmpty()) {
                SegmentedPillRow(GalleryFilter.entries, filter, { labels.getValue(it) }, onFilter, Modifier.padding(top = 4.dp, bottom = 12.dp), equalWidth = true)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !hasPermission -> Box(Modifier.padding(horizontal = 20.dp)) {
                    EmptyState(
                        title = stringResource(R.string.permission_title), body = stringResource(R.string.permission_body), icon = DaglichtIcons.Image,
                        action = { PrimaryPillButton(stringResource(R.string.permission_action), onRequestPermission) },
                    )
                }
                state.loading -> PillSpinner(Modifier.padding(32.dp).align(Alignment.TopCenter))
                state.photos.isEmpty() -> Box(Modifier.padding(horizontal = 20.dp)) {
                    EmptyState(stringResource(R.string.today_empty_title), stringResource(R.string.today_empty_body, formatDay(state.sinceMs)), icon = DaglichtIcons.Image)
                }
                shown.isEmpty() -> Box(Modifier.padding(horizontal = 20.dp)) {
                    val favorites = filter == GalleryFilter.Favorites
                    EmptyState(
                        stringResource(if (favorites) R.string.gallery_no_favorites_title else R.string.gallery_empty_filter),
                        if (favorites) stringResource(R.string.gallery_no_favorites_body) else "",
                        icon = if (favorites) DaglichtIcons.Favorite else DaglichtIcons.Image,
                    )
                }
                else -> PhotoGrid(entries, shown, todayLabel, yesterdayLabel, today, onSelect, onOpen) { label, photos -> cleanup = label to photos }
            }
            if (hasPermission && state.photos.isNotEmpty()) {
                SelectionBar(
                    state, onOverview, cleared.isNotEmpty(),
                    onClear = { val on = state.photos.filter { it.selectable && it.selected }; cleared = on; onSelect(on, false) },
                    onUndo = { onSelect(cleared, true); cleared = emptyList() },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 90.dp),
                )
            }
        }
    }
}

/** Wat Opruimen doet, in gewone woorden, met wat er blijft staan. Alleen de foto's van die ene dag. */
@Composable
private fun CleanupDialog(day: String, photos: List<Photo>, onConfirm: (List<Photo>) -> Unit, onDismiss: () -> Unit) {
    val remove = photos.filter { it.cleanable }
    val kept = photos.count { it.goesAlong && !it.item.isDng && it.status != Status.LightroomExport }
    val favorites = photos.count { it.status == Status.NotKept && it.item.isFavorite }
    val exports = photos.count { it.status == Status.LightroomExport || it.status == Status.Skipped }
    val raws = photos.count { it.item.isDng }
    val stays = buildList {
        if (kept > 0) add(stringResource(R.string.cleanup_kept, kept))
        if (favorites > 0) add(stringResource(R.string.cleanup_favorites, favorites))
        if (exports > 0) add(stringResource(R.string.cleanup_exports, exports))
        if (raws > 0) add(stringResource(R.string.cleanup_raws, raws))
    }
    val body = buildString {
        append(stringResource(R.string.cleanup_body, remove.size, day))
        if (kept == 0) append("\n\n").append(stringResource(R.string.cleanup_none_kept))
        if (stays.isNotEmpty()) append("\n\n").append(stringResource(R.string.cleanup_stays, stays.joinToString(", ")))
        append("\n\n").append(stringResource(R.string.cleanup_note))
    }
    DaglichtDialog(
        title = stringResource(R.string.cleanup_title, day),
        onDismiss = onDismiss,
        icon = DaglichtIcons.Delete,
        iconTint = MaterialTheme.colorScheme.error,
        body = body,
        closeLabel = stringResource(R.string.settings_back),
        actions = {
            SecondaryPillButton(stringResource(R.string.cleanup_cancel), onDismiss)
            PrimaryPillButton(stringResource(R.string.cleanup_confirm, remove.size), { onConfirm(remove) }, container = MaterialTheme.colorScheme.error, content = MaterialTheme.colorScheme.onError)
        },
    )
}

@Composable
private fun PhotoGrid(
    entries: List<Entry>,
    shown: List<Photo>,
    todayLabel: String,
    yesterdayLabel: String,
    today: LocalDate,
    onSelect: (List<Photo>, Boolean) -> Unit,
    onOpen: (Photo, List<Photo>) -> Unit,
    onCleanup: (String, List<Photo>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val byId = remember(shown) { shown.associateBy { it.item.id } }
    val select by rememberUpdatedState(onSelect)
    // Een lange druk eindigt ook in een tik; die mag de foto niet openen. De volgorde waarin tik en slepen klaar zijn
    // is niet vast, dus tellen we de tijd sinds het laatste lange-druk-moment.
    val lastLongPress = remember { longArrayOf(0L) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 204.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize().pointerInput(byId) {
            // Ingedrukt houden en slepen: de eerste foto bepaalt of het aan of uit wordt, alle foto's die je raakt volgen.
            var mode: Boolean? = null
            val seen = HashSet<Long>()
            fun hit(position: Offset): Photo? = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
                    position.y >= info.offset.y && position.y < info.offset.y + info.size.height
            }?.let { byId[it.key as? Long] }
            fun apply(photo: Photo) {
                if (!photo.selectable || !seen.add(photo.item.id)) return
                val on = mode ?: !photo.selected
                mode = on
                select(listOf(photo), on)
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    seen.clear(); mode = null; lastLongPress[0] = SystemClock.uptimeMillis()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    hit(position)?.let(::apply)
                },
                onDrag = { change, _ ->
                    change.consume()
                    hit(change.position)?.let(::apply)
                    // Bij de rand van het scherm schuift de galerij mee.
                    val edge = 160f
                    if (change.position.y < edge) scope.launch { gridState.scrollBy(-36f) }
                    else if (change.position.y > size.height - edge) scope.launch { gridState.scrollBy(36f) }
                },
                onDragEnd = { seen.clear(); mode = null; lastLongPress[0] = SystemClock.uptimeMillis() },
                onDragCancel = { seen.clear(); mode = null; lastLongPress[0] = SystemClock.uptimeMillis() },
            )
        },
    ) {
        items(entries, key = { if (it is Entry.Cell) it.photo.item.id else (it as Entry.Header).day.toString() }, span = { if (it is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { entry ->
            when (entry) {
                is Entry.Header -> {
                    val label = dayLabel(entry.day, today, todayLabel, yesterdayLabel)
                    DayHeader(label, entry.photos, onSelect) { onCleanup(label, entry.photos) }
                }
                is Entry.Cell -> Tile(entry.photo, { onSelect(listOf(entry.photo), !entry.photo.selected) }, {
                    if (SystemClock.uptimeMillis() - lastLongPress[0] > 500L) onOpen(entry.photo, shown)
                })
            }
        }
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate, todayLabel: String, yesterdayLabel: String) = when (day) {
    today -> todayLabel
    today.minusDays(1) -> yesterdayLabel
    else -> day.format(dayFormat)
}

/** De dag, met rechts één rondje dat alle foto's van die dag tegelijk aan of uit zet. */
@Composable
private fun DayHeader(label: String, photos: List<Photo>, onSelect: (List<Photo>, Boolean) -> Unit, onCleanup: () -> Unit) {
    val selectable = photos.filter { it.selectable }
    val all = selectable.isNotEmpty() && selectable.all { it.selected }
    val cleanable = photos.count { it.cleanable }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (cleanable > 0) {
            Row(
                Modifier.clip(PillShape).bouncyClickable(onClick = onCleanup).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(DaglichtIcons.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.cleanup_button, cleanable), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        if (selectable.isNotEmpty()) SelectCircle(all, { onSelect(selectable, !all) })
    }
}

/** Het rondje: leeg is uit, gevuld met een vinkje is aan. Het tikvlak is groter dan het rondje. */
@Composable
private fun SelectCircle(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clickable(role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
        val fill = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.32f)
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(fill).then(if (selected) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(DaglichtIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Tile(photo: Photo, onToggle: () -> Unit, onOpen: () -> Unit) {
    val selected = photo.selected
    val scale by animateFloatAsState(if (selected) 0.88f else 1f, DaglichtMotion.bouncy(), label = "tileScale")
    val corner by animateDpAsState(if (selected) 18.dp else 8.dp, DaglichtMotion.bouncy(), label = "tileCorner")
    val dim = if (photo.selectable) 1f else 0.45f
    val badge = when {
        photo.status == Status.Done -> R.string.tile_done
        photo.status == Status.RawEdited || photo.status == Status.OriginalExported -> R.string.tile_edited
        photo.status == Status.OriginalReplaced -> R.string.tile_original
        photo.item.isDng -> R.string.tile_raw
        else -> null
    }
    Box(Modifier.aspectRatio(1f).bouncyClickable(role = Role.Image, onClick = onOpen)) {
        PhotoThumb(
            photo.item, 360,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; alpha = dim; shape = RoundedCornerShape(corner); clip = true },
        )
        if (photo.selectable) SelectCircle(selected, onToggle, Modifier.align(Alignment.TopStart))
        if (photo.item.isFavorite) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                Icon(DaglichtIcons.Favorite, null, tint = Color(0xFFFF8FA3), modifier = Modifier.size(14.dp))
            }
        }
        if (badge != null) {
            Text(
                stringResource(badge), style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

/** Onderaan: hoeveel er bewaard is en wat dat scheelt. Het kruisje zet alles uit; tik op de tekst voor het overzicht. */
@Composable
private fun SelectionBar(
    state: UiState,
    onOverview: () -> Unit,
    justCleared: Boolean,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val s = state.summary
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (justCleared) {
                Text(stringResource(R.string.gallery_cleared), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                PrimaryPillButton(stringResource(R.string.gallery_undo), onUndo)
            } else {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (s.goingCount == 0) stringResource(R.string.gallery_none) else stringResource(R.string.gallery_selected, s.goingCount),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.bouncyClickable(onClick = onOverview),
                        )
                        if (s.goingCount > 0) DaglichtIconButton(DaglichtIcons.Close, stringResource(R.string.gallery_deselect_all), onClear, size = 32, iconSize = 16, background = MaterialTheme.colorScheme.surfaceContainerHighest)
                    }
                    if (s.goingCount > 0) Text(
                        stringResource(R.string.today_sizes, formatBytes(s.currentBytes), formatBytes(s.expectedBytes)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.bouncyClickable(onClick = onOverview),
                    )
                }
                PrimaryPillButton(stringResource(R.string.gallery_prepare), {
                    // Fase 1 leest alleen; het omzetten volgt na Mark's OK voor fase 2.
                    Toast.makeText(context, R.string.gallery_prepare_soon, Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}
