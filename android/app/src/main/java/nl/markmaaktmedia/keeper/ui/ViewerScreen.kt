package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.graph
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
import androidx.compose.ui.draw.drawBehind
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
/** Naar beneden slepen: over welk deel van de hoogte alles wegvalt (kleiner en vervagend), vanaf welk deel loslaten sluit, en hoe snel gooien ook sluit (px/s). */
private const val DISMISS_RANGE = 0.35f
private const val CLOSE_AT = 0.15f
private const val CLOSE_VELOCITY = 1800f
private const val BARS_FADE = 5f
/** De achtergrond vervaagt 1,8 keer zo snel als de foto verdwijnt, zodat je de galerij al vroeg terugziet. */
private const val BACKDROP_FADE = 1.8f
/** Zo klein is de foto als hij helemaal is weggesleept. */
private const val MIN_DRAG_SCALE = 0.6f

/**
 * Eén foto groot, met de acties erbij: bewaren of niet bewaren (allebei gaan vanzelf door naar de volgende, om snel door een
 * dag heen te kunnen), in Lightroom openen (RAW) en naar de prullenbak. Veeg om naar de volgende te gaan, knijp of tik twee keer om in te zoomen. De lijst staat vast bij het openen; wat er daarna
 * verandert (een vinkje, een verwijderde foto) komt uit [photos].
 */
@Composable
fun ViewerScreen(
    ids: List<Long>,
    startId: Long,
    photos: List<Photo>,
    onSelect: (Photo, Boolean) -> Unit,
    onReject: (Photo) -> Unit,
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
    val scope = rememberCoroutineScope()
    val defaultLook by context.graph.prefs.lookDefault.collectAsState(initial = "")
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.item.id == startId }.coerceAtLeast(0)) { list.size }
    var zoomed by remember { mutableStateOf(false) }
    // Naar beneden slepen sluit de foto, zoals in Immich: de foto blijft onder je vinger, ook zijwaarts en weer omhoog, en wordt kleiner
    // naarmate je lager komt. Los je hem los, dan sluit hij (ver genoeg of snel genoeg) of veert hij terug.
    var drag by remember { mutableStateOf(Offset.Zero) }
    var areaHeight by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    // Na het loslaten loopt dit door naar 1, zodat alles netjes wegvalt ook al is de foto maar tot de drempel gesleept.
    var closeFade by remember { mutableFloatStateOf(0f) }
    val settleJob = remember { arrayOfNulls<Job>(1) }
    /** 0 = op zijn plek, 1 = ver genoeg weggesleept om alles te laten wegvallen. */
    fun progress() = maxOf((drag.y.coerceAtLeast(0f) / (areaHeight * DISMISS_RANGE)).coerceIn(0f, 1f), closeFade)
    val backdrop = MaterialTheme.colorScheme.background
    fun springBack(velocity: Velocity) {
        settleJob[0] = scope.launch {
            animate(Offset.VectorConverter, drag, Offset.Zero, Offset(velocity.x, velocity.y), spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { value, _ -> drag = value }
        }
    }
    fun closeNow(velocity: Velocity) {
        closing = true
        onClose()
        // Het scherm wisselt al; de foto zakt nog even door terwijl hij met het scherm wegvervaagt.
        settleJob[0] = scope.launch {
            launch { animate(closeFade, 1f, animationSpec = tween(240)) { value, _ -> closeFade = value } }
            animate(Offset.VectorConverter, drag, drag + Offset(0f, areaHeight * 0.2f), Offset(velocity.x, velocity.y), tween(240, easing = LinearOutSlowInEasing)) { value, _ -> drag = value }
        }
    }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]
    // Knoppen en kop zijn al weg na een vijfde van het sleepbereik, zodat ze niet over de galerij eronder hangen.
    val barsFade: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = { alpha = 1f - (progress() * BARS_FADE).coerceAtMost(1f) }

    Column(
        Modifier.fillMaxSize()
            // De achtergrond wordt doorzichtig naarmate je de foto wegsleept: de galerij eronder komt langzaam terug.
            .drawBehind { drawRect(backdrop, alpha = 1f - (progress() * BACKDROP_FADE).coerceAtMost(1f)) }
            // De viewer ligt over de galerij: aanrakingen mogen er niet doorheen vallen.
            .pointerInput(Unit) {}
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().graphicsLayer(block = barsFade).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        // De vaste omlijsting vangt het gebaar op (in de Initial-ronde, vóór de pager); de pager erbinnen krijgt de verplaatsing.
        // Zo staan de aanraakcoördinaten niet mee te schuiven met de foto.
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { areaHeight = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (zoomed || closing) return@awaitEachGesture
                        settleJob[0]?.cancel() // een foto die nog terugveert, pak je gewoon weer vast
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var travelled = Offset.Zero
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Een tweede vinger is knijpen om te zoomen: daar blijven we af.
                            if (!dragging && event.changes.size > 1) return@awaitEachGesture
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            if (!dragging) {
                                travelled += delta
                                if (travelled.getDistance() > slop) {
                                    // Alleen een beweging die vooral naar beneden gaat sleept de foto weg; zijwaarts is voor de pager.
                                    if (travelled.y > 0f && abs(travelled.y) > abs(travelled.x)) dragging = true else return@awaitEachGesture
                                }
                            } else {
                                drag += delta
                                change.consume()
                            }
                        }
                        if (!dragging) return@awaitEachGesture
                        val velocity = tracker.calculateVelocity()
                        // Ver genoeg gesleept, of snel naar beneden gegooid, sluit. Terug omhoog en loslaten laat hem staan.
                        if (drag.y > areaHeight * CLOSE_AT || (velocity.y > CLOSE_VELOCITY && drag.y > slop * 2)) closeNow(velocity) else springBack(velocity)
                    }
                },
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val scale = 1f - (1f - MIN_DRAG_SCALE) * progress()
                    translationX = drag.x; translationY = drag.y; scaleX = scale; scaleY = scale
                },
                userScrollEnabled = !zoomed,
                key = { list.getOrNull(it)?.item?.id ?: it },
            ) { page ->
                val photo = list.getOrNull(page)
                if (photo != null) ZoomablePhoto(photo, active = page == pager.currentPage, onZoomed = { zoomed = it })
            }
        }
        Column(Modifier.graphicsLayer(block = barsFade).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(current.status.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${formatMegapixels(current.item.megapixels)}  ·  ${formatBytes(current.item.size)}  ·  ${formatDay(current.takenMs)} ${formatTime(current.takenMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Bewaren en niet bewaren zetten de keuze en gaan door naar de volgende. De laatste foto blijft staan.
            fun next() { if (pager.currentPage < list.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            // IntrinsicSize.Min: de prullenbak is precies zo hoog als de pillen ernaast, ook bij een ander lettertype.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current.selectable) {
                    if (current.discarded) PrimaryPillButton(
                        stringResource(R.string.viewer_discarded), { onReject(current); next() }, Modifier.weight(1f), icon = KeeperIcons.Close,
                        container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer,
                    ) else SecondaryPillButton(stringResource(R.string.viewer_discard), { onReject(current); next() }, Modifier.weight(1f), icon = KeeperIcons.Close)
                    if (current.selected) PrimaryPillButton(stringResource(R.string.viewer_kept), { next() }, Modifier.weight(1f), icon = KeeperIcons.Check)
                    else SecondaryPillButton(stringResource(R.string.viewer_keep), { onSelect(current, true); next() }, Modifier.weight(1f), icon = KeeperIcons.Check)
                } else Box(Modifier.weight(1f))
                // Naar de prullenbak, na Android's eigen bevestiging; terug te zetten.
                KeeperIconButton(
                    KeeperIcons.Delete, stringResource(R.string.review_trash), { onTrash(current) },
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
                    size = 48, iconSize = 22, background = MaterialTheme.colorScheme.errorContainer, tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (current.item.isDng) SecondaryPillButton(stringResource(R.string.review_lightroom), { openInLightroom(scope, context, current, photos, defaultLook) }, Modifier.fillMaxWidth(), icon = KeeperIcons.OpenInNew)
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
                String.format(Locale.getDefault(), "%.1f×", scale), style = MaterialTheme.typography.labelMedium, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(PillShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()
