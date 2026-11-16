package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.components.KeeperIconButton
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.PillShape
import java.util.Locale

/** Zoomniveau van een dubbele tik, en het hoogste dat knijpen toelaat. */
private const val DOUBLE_TAP_ZOOM = 3f
private const val MAX_ZOOM = 8f
/** Langste kant van het scherpe beeld dat bij inzoomen wordt geladen. */
private const val DETAIL_EDGE = 4096
/** Het beeld dat je ziet zonder in te zoomen. */
private const val PREVIEW_EDGE = 1800

/**
 * Eén foto groot, met de acties erbij: bewaren, in Lightroom openen (RAW) en naar de prullenbak. Veeg om naar de
 * volgende te gaan, knijp of tik twee keer om in te zoomen. De lijst staat vast bij het openen; wat er daarna
 * verandert (een vinkje, een verwijderde foto) komt uit [photos].
 */
@Composable
fun ViewerScreen(
    ids: List<Long>,
    startId: Long,
    photos: List<Photo>,
    onSelect: (Photo, Boolean) -> Unit,
    onTrash: (Photo) -> Unit,
    onClose: () -> Unit,
) {
    val byId = remember(photos) { photos.associateBy { it.item.id } }
    val list = ids.mapNotNull { byId[it] }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.item.id == startId }.coerceAtLeast(0)) { list.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            KeeperIconButton(KeeperIcons.Back, stringResource(R.string.settings_back), onClose)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(current.item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(R.string.viewer_progress, pager.currentPage + 1, list.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.item.isFavorite) Icon(KeeperIcons.Favorite, stringResource(R.string.filter_favorites), tint = Color(0xFFFF8FA3), modifier = Modifier.padding(end = 14.dp).size(20.dp))
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = !zoomed,
            key = { list.getOrNull(it)?.item?.id ?: it },
        ) { page ->
            val photo = list.getOrNull(page)
            if (photo != null) ZoomablePhoto(photo, active = page == pager.currentPage, onZoomed = { zoomed = it })
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(current.status.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${formatMegapixels(current.item.megapixels)}  ·  ${formatBytes(current.item.size)}  ·  ${formatDay(current.takenMs)} ${formatTime(current.takenMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current.selectable) {
                    if (current.selected) PrimaryPillButton(stringResource(R.string.viewer_kept), { onSelect(current, false) }, Modifier.weight(1f), icon = KeeperIcons.Check)
                    else SecondaryPillButton(stringResource(R.string.viewer_keep), { onSelect(current, true) }, Modifier.weight(1f), icon = KeeperIcons.Check)
                } else Box(Modifier.weight(1f))
                if (current.item.isDng) SecondaryPillButton(stringResource(R.string.review_lightroom), { openInLightroom(context, current) }, icon = KeeperIcons.OpenInNew)
                // Naar de prullenbak, na Android's eigen bevestiging; terug te zetten.
                KeeperIconButton(
                    KeeperIcons.Delete, stringResource(R.string.review_trash), { onTrash(current) },
                    size = 52, iconSize = 22, background = MaterialTheme.colorScheme.errorContainer, tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * Een foto die je kunt inzoomen. Eerst staat er een snel voorbeeld; zodra je inzoomt komt er een scherper beeld
 * (tot [DETAIL_EDGE] pixels) voor in de plaats, om de kwaliteit te beoordelen. Met één vinger veegt de pager door;
 * pas als er is ingezoomd, of met twee vingers, nemen we het gebaar over.
 */
@Composable
private fun ZoomablePhoto(photo: Photo, active: Boolean, onZoomed: (Boolean) -> Unit) {
    val scanner = LocalContext.current.graph.scanner
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val preview: ImageBitmap? by produceState(scanner.cachedLarge(photo.item, PREVIEW_EDGE)?.asImageBitmap() ?: scanner.cached(photo.item, 360)?.asImageBitmap(), photo.item.id) {
        scanner.large(photo.item, PREVIEW_EDGE)?.let { value = it.asImageBitmap() }
    }
    var detail by remember(photo.item.id) { mutableStateOf<ImageBitmap?>(null) }
    val zoomedIn = scale > 1.05f
    LaunchedEffect(photo.item.id, zoomedIn) {
        if (zoomedIn && detail == null) detail = scanner.large(photo.item, DETAIL_EDGE)?.asImageBitmap()
    }
    LaunchedEffect(zoomedIn, active) { if (active) onZoomed(zoomedIn) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.05f) { scale = 1f; offset = Offset.Zero }
                    else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        scale = DOUBLE_TAP_ZOOM
                        offset = clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (scale > 1f || event.changes.size > 1) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            if (centroid.isSpecified()) {
                                // Het punt onder je vingers blijft onder je vingers.
                                val d = centroid - Offset(size.width / 2f, size.height / 2f)
                                val r = newScale / scale
                                offset = clamp(d - (d - offset) * r + pan, newScale)
                            }
                            scale = newScale
                            if (newScale <= 1f) offset = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        (detail ?: preview)?.let {
            Image(
                it, null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit,
            )
        }
        if (zoomedIn) {
            Text(
                String.format(Locale("nl", "NL"), "%.1f×", scale), style = MaterialTheme.typography.labelMedium, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()
