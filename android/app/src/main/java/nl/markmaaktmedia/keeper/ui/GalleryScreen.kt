package nl.markmaaktmedia.keeper.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
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
import nl.markmaaktmedia.keeper.graph
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.UiState
import nl.markmaaktmedia.keeper.scan.Edit
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.components.KeeperDialog
import nl.markmaaktmedia.keeper.ui.components.KeeperIconButton
import nl.markmaaktmedia.keeper.ui.components.EmptyState
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.components.PillSpinner
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SegmentedPillRow
import nl.markmaaktmedia.keeper.ui.components.bouncyClickable
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.KeeperMotion
import nl.markmaaktmedia.keeper.ui.theme.PillShape
import nl.markmaaktmedia.keeper.ui.update.UpdateBanner
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
        Selected -> p.kept
        Raw -> p.item.isDng
    }
}

// Een leeg scherm staat optisch iets boven het midden, en niet achter de selectiebalk en navigatiebalk onderaan.
private val EMPTY_LIFT = 240.dp

/** Bewaard en niet bewaard hebben eigen kleuren en staan rechtsonder, zodat ze nooit op de selectie lijken (blauw, linksboven). */
private val KeptGreen = Color(0xFF2E9E57)
private val RejectedRed = Color(0xFFD64545)

private sealed interface Entry {
    data class Header(val day: LocalDate, val photos: List<Photo>) : Entry
    data class Cell(val photo: Photo) : Entry
}

private val zone: ZoneId get() = ZoneId.systemDefault()
// In de taal van de app, op het moment van gebruiken.
internal fun dayFormat() = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

/**
 * De startpagina: een galerij van wat er nieuw is, met twee gescheiden dingen.
 *
 * - Een beslissing per foto: bewaard (groen vinkje rechtsonder), niet bewaard (rood kruisje rechtsonder) of nog niet bekeken (niets).
 *   Die blijft staan en bepaalt wat er meegaat.
 * - Een selectie: tijdelijk, om meerdere foto's tegelijk iets te laten doen. Houd een foto ingedrukt (en sleep over een reeks), dan
 *   krijgen de gekozen foto's links boven een blauw vinkje en verkleinen ze. Vanuit de balk onderaan bewaar je ze allemaal, doe je
 *   ze allemaal weg of stuur je ze naar de prullenbak. Een selectie begint elke keer leeg en verandert zelf niets.
 */
@Composable
fun GalleryScreen(
    state: UiState,
    filter: GalleryFilter,
    onFilter: (GalleryFilter) -> Unit,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onKeep: (List<Photo>) -> Unit,
    onReject: (List<Photo>) -> Unit,
    onOpen: (Photo, List<Photo>) -> Unit,
    onOverview: () -> Unit,
    onTrashMany: (List<Photo>) -> Unit,
    /** Start het omzetten van alles wat meegaat. */
    onConvert: () -> Unit,
    /** Er loopt al een run: de knop doet dan niets. */
    converting: Boolean,
    paused: Boolean,
    /** Alles wat meegaat weer uitzetten, om opnieuw te beginnen. De galerij vraagt zelf eerst om een bevestiging. */
    onClearSelection: () -> Unit,
    /** De tegels melden hier waar ze staan, voor de vlucht van de foto terug naar zijn tegel. */
    bounds: TileBounds = TileBounds(),
    /** De foto die nu groot open staat: zijn tegel blijft leeg (het gat waar hij naartoe terugvliegt) en de lijst scrolt ernaartoe. */
    hiddenId: Long? = null,
    /** Opruimen heeft zijn eigen bevestiging al gehad (de dialoog hieronder): dit gaat zonder tweede vraag naar de prullenbak. */
    onCleanupTrash: (List<Photo>) -> Unit = onTrashMany,
) {
    // Opruimen: welke dag en welke foto's van die dag. Pas na een bevestiging gaat er iets naar de prullenbak.
    var cleanup by remember { mutableStateOf<Pair<String, List<Photo>>?>(null) }
    // De selectie. Leeg zodra je de modus verlaat, en de modus eindigt vanzelf als je de laatste foto uitzet.
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val byId = remember(state.photos) { state.photos.associateBy { it.item.id } }
    val pickedPhotos = remember(picked, byId) { picked.mapNotNull { byId[it] }.filter { it.selectable } }
    fun setPicked(photos: List<Photo>, on: Boolean) {
        val ids = photos.filter { it.selectable }.map { it.item.id }
        picked = if (on) picked + ids else picked - ids.toSet()
    }
    // Een tik of dagrondje: zet aan of uit en sluit de modus als er niets meer gekozen is.
    fun pick(photos: List<Photo>, on: Boolean) { setPicked(photos, on); if (picked.isEmpty()) selecting = false }
    fun endSelecting() { picked = emptySet(); selecting = false }
    BackHandler(enabled = selecting) { endSelecting() }

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
    // Alleen wat Mark zelf beoordeelt: camera-JPG's en RAW's, niet de Lightroom-exports.
    val (toReview, notKept) = remember(state.photos) {
        val reviewable = state.photos.filter { it.selectable && !it.defaultsOn }
        reviewable.count { !it.kept && !it.discarded } to reviewable.count { it.discarded }
    }
    var askClear by remember { mutableStateOf(false) }
    if (askClear) {
        ClearSelectionDialog(state.summary.goingCount, { askClear = false; onClearSelection() }, { askClear = false })
    }
    var askEdited by remember { mutableStateOf(false) }
    if (askEdited) {
        val withEdit = pickedPhotos.filter { it.askBeforeKeeping }
        EditedKeepDialog(
            withEdit.size,
            // Alleen de bewerkte versie: de rest wordt bewaard, de originelen met een bewerking krijgen het kruisje.
            onEditedOnly = { askEdited = false; onKeep(pickedPhotos - withEdit.toSet()); onReject(withEdit); endSelecting() },
            // Alleen het origineel: de bewerkingen die erbij horen gaan niet mee (kruisje) en blijven in Lightroom of de Galerij staan.
            onOriginalOnly = {
                askEdited = false
                val edits = withEdit.flatMap { it.editIds }.toSet()
                onKeep(pickedPhotos.filter { it.item.id !in edits })
                onReject(edits.mapNotNull { byId[it] })
                endSelecting()
            },
            onBoth = { askEdited = false; onKeep(pickedPhotos); endSelecting() },
            onDismiss = { askEdited = false },
        )
    }
    cleanup?.let { (day, photos) ->
        CleanupDialog(day, photos, { remove -> cleanup = null; onCleanupTrash(remove) }, { cleanup = null })
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            UpdateBanner(Modifier.padding(top = 8.dp))
            ScreenHeader(
                stringResource(R.string.gallery_title),
                if (hasPermission && state.photos.isNotEmpty()) stringResource(R.string.gallery_counts, toReview, state.summary.goingCount, notKept) else null,
            )
            if (hasPermission && state.photos.isNotEmpty()) {
                SegmentedPillRow(GalleryFilter.entries, filter, { labels.getValue(it) }, onFilter, Modifier.padding(top = 4.dp, bottom = 12.dp), equalWidth = true)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !hasPermission -> Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = EMPTY_LIFT)) {
                    EmptyState(
                        title = stringResource(R.string.permission_title), body = stringResource(R.string.permission_body), icon = KeeperIcons.Image,
                        action = { PrimaryPillButton(stringResource(R.string.permission_action), onRequestPermission) },
                    )
                }
                state.loading -> PillSpinner(Modifier.padding(32.dp).align(Alignment.TopCenter))
                state.photos.isEmpty() -> Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = EMPTY_LIFT)) {
                    EmptyState(stringResource(R.string.today_empty_title), stringResource(R.string.today_empty_body, formatDay(state.sinceMs)), icon = KeeperIcons.Image)
                }
                shown.isEmpty() -> Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = EMPTY_LIFT)) {
                    val favorites = filter == GalleryFilter.Favorites
                    EmptyState(
                        stringResource(if (favorites) R.string.gallery_no_favorites_title else R.string.gallery_empty_filter),
                        if (favorites) stringResource(R.string.gallery_no_favorites_body) else "",
                        icon = if (favorites) KeeperIcons.Favorite else KeeperIcons.Image,
                    )
                }
                else -> PhotoGrid(
                    entries, shown, todayLabel, yesterdayLabel, today, selecting, picked,
                    isPicked = { it in picked },
                    onStartSelecting = { selecting = true },
                    onSetPicked = ::setPicked,
                    onPick = ::pick,
                    onSelectionEnd = { if (picked.isEmpty()) selecting = false },
                    onOpen = onOpen,
                    bounds = bounds,
                    hiddenId = hiddenId,
                ) { label, photos -> cleanup = label to photos }
            }
            if (hasPermission && state.photos.isNotEmpty()) {
                BottomBar(
                    state, selecting, pickedPhotos, onOverview,
                    onConvert = onConvert, converting = converting, paused = paused,
                    onClear = ::endSelecting,
                    onClearAll = { askClear = true },
                    // Zitten er originelen met een bewerkte versie bij, dan eerst vragen of Keeper die ook zelf mag exporteren.
                    onKeep = { if (pickedPhotos.any { it.askBeforeKeeping }) askEdited = true else { onKeep(pickedPhotos); endSelecting() } },
                    onReject = { onReject(pickedPhotos); endSelecting() },
                    onTrash = { onTrashMany(pickedPhotos); endSelecting() },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 90.dp),
                )
            }
        }
    }
}

/** Opnieuw beginnen: vraagt eerst om een bevestiging, want alle keuzes gaan in één keer weg. */
@Composable
private fun ClearSelectionDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    KeeperDialog(
        title = stringResource(R.string.gallery_clear_title),
        onDismiss = onDismiss,
        icon = KeeperIcons.Refresh,
        iconTint = MaterialTheme.colorScheme.error,
        body = resources.getQuantityString(R.plurals.gallery_clear_body, count, count),
        closeLabel = stringResource(R.string.settings_back),
        actions = {
            SecondaryPillButton(stringResource(R.string.cleanup_cancel), onDismiss)
            PrimaryPillButton(stringResource(R.string.gallery_clear_confirm), onConfirm, container = MaterialTheme.colorScheme.error, content = MaterialTheme.colorScheme.onError)
        },
    )
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
        // Met Mediabeheer vraagt Android niets meer: deze dialoog is dan de enige bevestiging.
        val androidAsks = !LocalContext.current.graph.hider.canManageMedia
        append("\n\n").append(stringResource(if (androidAsks) R.string.cleanup_note else R.string.cleanup_note_managed))
    }
    KeeperDialog(
        title = stringResource(R.string.cleanup_title, day),
        onDismiss = onDismiss,
        icon = KeeperIcons.Delete,
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
    selecting: Boolean,
    picked: Set<Long>,
    isPicked: (Long) -> Boolean,
    onStartSelecting: () -> Unit,
    onSetPicked: (List<Photo>, Boolean) -> Unit,
    onPick: (List<Photo>, Boolean) -> Unit,
    onSelectionEnd: () -> Unit,
    onOpen: (Photo, List<Photo>) -> Unit,
    bounds: TileBounds,
    hiddenId: Long?,
    onCleanup: (String, List<Photo>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Veegt Mark in de viewer naar een foto die in de galerij niet in beeld is, dan scrolt de lijst mee (zoals in Immich),
    // zodat er bij het sluiten een tegel is om naartoe te vliegen. Onder de balk onderaan telt als niet in beeld.
    LaunchedEffect(hiddenId) {
        val id = hiddenId ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it is Entry.Cell && it.photo.item.id == id }
        if (index < 0) return@LaunchedEffect
        val info = gridState.layoutInfo
        val limit = info.viewportSize.height - info.afterContentPadding
        val visible = info.visibleItemsInfo.firstOrNull { it.key == id }
        if (visible == null || visible.offset.y < 0 || visible.offset.y + visible.size.height > limit) {
            val height = visible?.size?.height ?: info.visibleItemsInfo.firstOrNull { it.key is Long }?.size?.height ?: 0
            gridState.animateScrollToItem(index, scrollOffset = -((limit - height) / 2).coerceAtLeast(0))
        }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val byId by rememberUpdatedState(remember(shown) { shown.associateBy { it.item.id } })
    val order by rememberUpdatedState(shown)
    val indexOf by rememberUpdatedState(remember(shown) { shown.withIndex().associate { it.value.item.id to it.index } })
    val setPicked by rememberUpdatedState(onSetPicked)
    val pickedNow by rememberUpdatedState(isPicked)
    val startSelecting by rememberUpdatedState(onStartSelecting)
    val selectionEnd by rememberUpdatedState(onSelectionEnd)
    // Een lange druk eindigt ook in een tik; die mag de foto niet openen. De volgorde waarin tik en slepen klaar zijn
    // is niet vast, dus tellen we de tijd sinds het laatste lange-druk-moment.
    val lastLongPress = remember { longArrayOf(0L) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 204.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Ingedrukt houden en slepen, zoals in Google Foto's: de foto waar je begint is het anker, en alles in leesvolgorde tussen
            // het anker en waar je vinger nu is wordt aan of uit gezet (de eerste foto bepaalt welk van de twee). Schuif je terug,
            // dan krimpt de reeks en krijgen de foto's die er niet meer in zitten hun oude stand terug.
            // De sleutel is Unit: een nieuwe fotolijst mag het lopende gebaar niet afbreken, de lijst wordt bijgewerkt gelezen.
            // Dit gebaar kijkt in de Initial-fase, vóór het raster zelf: anders pakt het raster de sleepbeweging op om te scrollen.
            var mode: Boolean? = null
            var anchor = -1
            var lastIndex = -1
            var touched = HashSet<Long>()
            val original = HashMap<Long, Boolean>()
            fun reset() { mode = null; anchor = -1; lastIndex = -1; touched = HashSet(); original.clear() }
            fun hit(position: Offset): Photo? {
                val tiles = gridState.layoutInfo.visibleItemsInfo.filter { it.key is Long }
                val at = tiles.firstOrNull { info ->
                    position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
                        position.y >= info.offset.y && position.y < info.offset.y + info.size.height
                } ?: tiles.filter { position.y >= it.offset.y && position.y < it.offset.y + it.size.height }
                    // tussen twee tegels of naast de laatste van een rij: de dichtstbijzijnde tegel in die rij
                    .minByOrNull { kotlin.math.abs(it.offset.x + it.size.width / 2f - position.x) }
                return at?.let { byId[it.key as? Long] }
            }
            fun extend(photo: Photo) {
                val j = indexOf[photo.item.id] ?: return
                if (j == lastIndex) return
                lastIndex = j
                if (anchor < 0) anchor = j
                val inRange = order.subList(minOf(anchor, j), maxOf(anchor, j) + 1).filter { it.selectable }
                val on = mode ?: inRange.firstOrNull()?.let { !pickedNow(it.item.id) } ?: return
                mode = on
                val ids = inRange.mapTo(HashSet()) { it.item.id }
                val add = inRange.filter { it.item.id !in touched }
                add.forEach { original.getOrPut(it.item.id) { pickedNow(it.item.id) } }
                if (add.isNotEmpty()) setPicked(add, on)
                val drop = touched.filter { it !in ids }.mapNotNull { byId[it] }
                for ((was, group) in drop.groupBy { original.getValue(it.item.id) }) setPicked(group, was)
                touched = ids
            }
            val slop = viewConfiguration.touchSlop
            val edge = 180f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Een lange druk is: de vinger blijft liggen, binnen de slop, tot de time-out. Beweegt hij of gaat hij weg, dan is het een tik of scroll.
                val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!change.pressed || (change.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (moved != null) return@awaitEachGesture
                reset(); lastLongPress[0] = SystemClock.uptimeMillis(); startSelecting()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                hit(down.position)?.let(::extend)
                var last = down.position
                var direction = 0
                var speed = 0f
                // Blijft de vinger bij de rand liggen, dan schuift de galerij door en volgen de foto's die langskomen.
                val scroller = scope.launch {
                    while (true) {
                        if (direction != 0) {
                            gridState.scrollBy(direction * speed)
                            hit(last)?.let(::extend)
                            delay(16)
                        } else delay(100)
                    }
                }
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        last = change.position
                        hit(last)?.let(::extend)
                        val top = last.y
                        val bottom = size.height - last.y
                        when {
                            top < edge -> { direction = -1; speed = 8f + (edge - top).coerceIn(0f, edge) / edge * 34f }
                            bottom < edge -> { direction = 1; speed = 8f + (edge - bottom).coerceIn(0f, edge) / edge * 34f }
                            else -> direction = 0
                        }
                    }
                } finally {
                    scroller.cancel()
                    reset(); lastLongPress[0] = SystemClock.uptimeMillis()
                    selectionEnd()
                }
            }
        },
    ) {
        items(entries, key = { if (it is Entry.Cell) it.photo.item.id else (it as Entry.Header).day.toString() }, span = { if (it is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { entry ->
            when (entry) {
                is Entry.Header -> {
                    val label = dayLabel(entry.day, today, todayLabel, yesterdayLabel)
                    DayHeader(label, entry.photos, selecting, isPicked, onPick) { onCleanup(label, entry.photos) }
                }
                is Entry.Cell -> {
                    val photo = entry.photo
                    val isOn = photo.item.id in picked
                    Tile(photo, selecting, isOn, hiddenId == photo.item.id, bounds, { onPick(listOf(photo), !isOn) }, {
                        // Een tik na een lange druk telt niet. In selectiemodus zet een tik de foto aan of uit, anders opent hij groot.
                        if (SystemClock.uptimeMillis() - lastLongPress[0] > 500L) {
                            if (selecting) { if (photo.selectable) onPick(listOf(photo), !isOn) } else onOpen(photo, shown)
                        }
                    })
                }
            }
        }
    }
}

internal fun dayLabel(day: LocalDate, today: LocalDate, todayLabel: String, yesterdayLabel: String) = when (day) {
    today -> todayLabel
    today.minusDays(1) -> yesterdayLabel
    else -> day.format(dayFormat())
}

/** De dag, met rechts één rondje dat alle foto's van die dag tegelijk selecteert. */
@Composable
private fun DayHeader(
    label: String, photos: List<Photo>, selecting: Boolean, isPicked: (Long) -> Boolean,
    onPick: (List<Photo>, Boolean) -> Unit, onCleanup: () -> Unit,
) {
    val selectable = photos.filter { it.selectable }
    val all = selectable.isNotEmpty() && selectable.all { isPicked(it.item.id) }
    val cleanable = photos.count { it.cleanable }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (cleanable > 0 && !selecting) {
            Row(
                Modifier.clip(PillShape).bouncyClickable(onClick = onCleanup).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(KeeperIcons.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.cleanup_button, cleanable), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        if (selecting && selectable.isNotEmpty()) SelectCircle(all, { onPick(selectable, !all) })
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
            if (selected) Icon(KeeperIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** Rechtsonder: groen vinkje voor bewaard, rood kruisje voor bewust niet bewaard. Nooit blauw en nooit linksboven, dat is de selectie. */
@Composable
private fun DecisionBadge(kept: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(if (kept) KeptGreen else RejectedRed), contentAlignment = Alignment.Center) {
        Icon(
            if (kept) KeeperIcons.Check else KeeperIcons.Close, stringResource(if (kept) R.string.tile_kept else R.string.tile_discarded),
            tint = Color.White, modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun Tile(photo: Photo, selecting: Boolean, picked: Boolean, hidden: Boolean, bounds: TileBounds, onToggle: () -> Unit, onOpen: () -> Unit) {
    // Voor de vlucht van de foto terug naar deze tegel (schermcoördinaten). Een tegel die uit beeld raakt, meldt zich af.
    DisposableEffect(photo.item.id) { onDispose { bounds.tiles.remove(photo.item.id) } }
    // Alleen een gekozen foto (selectie) wordt kleiner met ronde hoeken. Bewaard of niet bewaard verandert de tegel zelf niet.
    val scale by animateFloatAsState(if (picked) 0.88f else 1f, KeeperMotion.bouncy(), label = "tileScale")
    val corner by animateDpAsState(if (picked) 18.dp else 8.dp, KeeperMotion.bouncy(), label = "tileCorner")
    val dim = if (photo.selectable) 1f else 0.45f
    // "Bewerkt" gaat voor "RAW": een RAW met een Lightroom-export laat zien dat er een bewerking bestaat, ook als hij zelf meegaat.
    val badge = when {
        photo.status == Status.Done -> R.string.tile_done
        photo.edit == Edit.Exported -> R.string.tile_edited
        photo.edit == Edit.Replaced -> R.string.tile_original
        photo.item.isDng -> R.string.tile_raw
        else -> null
    }
    Box(Modifier.aspectRatio(1f).onGloballyPositioned { if (bounds.tiles[photo.item.id] !== it) bounds.tiles[photo.item.id] = it }.bouncyClickable(role = Role.Image, onClick = onOpen)) {
        // Foto, vinkje, hartje en labels staan in één laag die samen kleiner wordt, zodat ze op dezelfde plek op de foto blijven.
        // Staat de foto groot open, dan is de tegel leeg: hij vliegt er bij het sluiten naar terug.
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (hidden) 0f else 1f }) {
            PhotoThumb(photo.item, 360, Modifier.fillMaxSize().graphicsLayer { alpha = dim }.clip(RoundedCornerShape(corner)))
            if (selecting && photo.selectable) SelectCircle(picked, onToggle, Modifier.align(Alignment.TopStart))
            if (photo.item.isFavorite) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
                    Icon(KeeperIcons.Favorite, null, tint = Color(0xFFFF8FA3), modifier = Modifier.size(14.dp))
                }
            }
            if (badge != null) {
                Text(
                    stringResource(badge), style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (photo.selectable && (photo.kept || photo.discarded)) DecisionBadge(photo.kept, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

/**
 * Onderaan, in twee standen. Zonder selectie: hoeveel er bewaard is en wat dat scheelt, tik voor het overzicht, en Convert.
 * Met selectie: wat je met de gekozen foto's wilt doen. Bewaren en niet bewaren zijn beslissingen, de prullenbak vraagt eerst
 * Android's bevestiging. Het kruisje wist alleen de selectie.
 */
@Composable
private fun BottomBar(
    state: UiState,
    selecting: Boolean,
    picked: List<Photo>,
    onOverview: () -> Unit,
    onConvert: () -> Unit,
    converting: Boolean,
    paused: Boolean,
    onClear: () -> Unit,
    onClearAll: () -> Unit,
    onKeep: () -> Unit,
    onReject: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val s = state.summary
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Crossfade(selecting, label = "bottomBar") { isSelecting ->
            if (isSelecting) {
                val none = picked.isEmpty()
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeeperIconButton(KeeperIcons.Close, stringResource(R.string.gallery_deselect_all), onClear, size = 44, iconSize = 20, background = MaterialTheme.colorScheme.surfaceContainerHighest)
                        Text(
                            if (none) stringResource(R.string.gallery_pick_hint) else stringResource(R.string.gallery_picked, picked.size),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 14.dp),
                        )
                        KeeperIconButton(KeeperIcons.Delete, stringResource(R.string.review_trash), onTrash, size = 44, iconSize = 20, enabled = !none, tint = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryPillButton(
                            stringResource(R.string.viewer_discard), onReject, Modifier.weight(1f), icon = KeeperIcons.Close, enabled = !none,
                            container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurface,
                        )
                        PrimaryPillButton(
                            stringResource(R.string.viewer_keep), onKeep, Modifier.weight(1f), icon = KeeperIcons.Check, enabled = !none,
                            container = KeptGreen, content = Color.White,
                        )
                    }
                }
            } else {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (s.goingCount == 0) stringResource(R.string.gallery_none) else stringResource(R.string.gallery_selected, s.goingCount),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            modifier = Modifier.bouncyClickable(onClick = onOverview),
                        )
                        if (s.goingCount > 0) Text(
                            stringResource(R.string.today_sizes, formatBytes(s.currentBytes), formatBytes(s.expectedBytes)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            modifier = Modifier.bouncyClickable(onClick = onOverview),
                        )
                    }
                    // Opnieuw beginnen. Niet tijdens het omzetten: dan loopt er een run op de keuzes van dat moment.
                    if (s.goingCount > 0 && !converting) {
                        KeeperIconButton(KeeperIcons.Refresh, stringResource(R.string.gallery_clear), onClearAll, size = 44, iconSize = 20, background = MaterialTheme.colorScheme.surfaceContainerHighest)
                    }
                    PrimaryPillButton(stringResource(if (paused) R.string.gallery_continue else R.string.gallery_prepare), onConvert, enabled = s.goingCount > 0 && !converting)
                }
            }
        }
    }
}
