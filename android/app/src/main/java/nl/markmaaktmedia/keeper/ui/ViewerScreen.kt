package nl.markmaaktmedia.keeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import android.os.Build
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import nl.markmaaktmedia.keeper.ui.components.bouncyClickable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.keeper.convert.ConvertPreview
import nl.markmaaktmedia.keeper.convert.HdrPreview
import nl.markmaaktmedia.keeper.convert.hdrPreviewSupported
import nl.markmaaktmedia.keeper.convert.ConvertSettings
import nl.markmaaktmedia.keeper.convert.JpegSource
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.ToneMap
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
import nl.markmaaktmedia.keeper.ui.theme.ConnectedEndShape
import nl.markmaaktmedia.keeper.ui.theme.ConnectedGap
import nl.markmaaktmedia.keeper.ui.theme.ConnectedStartShape
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.KeeperMotion
import nl.markmaaktmedia.keeper.ui.theme.PillShape
import java.util.Locale

/** Zoomniveau van een dubbele tik, en het hoogste dat knijpen toelaat. */
private const val DOUBLE_TAP_ZOOM = 3f
private const val MAX_ZOOM = 8f
/** Hoe lang dubbeltikken om in of uit te zoomen duurt. */
private const val ZOOM_MS = 300
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
/** Hoe lang de foto over naar zijn tegel doet (ms), en hoe rond de hoeken van de tegel zijn (zoals in de galerij). */
private const val HERO_MS = 300
private val TILE_CORNER = 8.dp

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
    /** Het kruisje weer weghalen: nog een keer op "niet bewaren" tikken. */
    onUndoReject: (Photo) -> Unit = {},
    /** Waar elke tegel in de galerij staat; de foto vliegt bij het sluiten terug naar zijn eigen tegel. */
    bounds: TileBounds = TileBounds(),
    /** De foto die nu in beeld is, zodat de galerij zijn tegel leeg kan laten en er naartoe kan scrollen. */
    onCurrent: (Long) -> Unit = {},
    /** Alleen deze foto omzetten, zonder hem te bewaren; staat er al een versie, dan komt er een nieuwe naast. */
    onConvertOne: (Photo) -> Unit = {},
    /** Er loopt al een run: dan kan er geen tweede bij. */
    converting: Boolean = false,
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
    // HDR-sterkte van één foto: het paneel staat onder de foto, die live meebeweegt (zie ZoomablePhoto). De keuze wordt bij loslaten opgeslagen.
    val strengthPrefs = context.graph.prefs
    val defaultStrength by strengthPrefs.hdrStrength.collectAsState(initial = 100)
    val strengthByName by strengthPrefs.hdrStrengthByName.collectAsState(initial = emptyMap())
    // HDR of SDR: de instelling "HDR behouden", of wat voor deze foto gekozen is. Bij HDR toont het voorbeeld de HDR zelf met de gain map op de gekozen
    // sterkte; bij SDR wat de SDR-omzetting zou maken.
    val keepHdr by strengthPrefs.keepHdr.collectAsState(initial = false)
    val keepByName by strengthPrefs.keepHdrByName.collectAsState(initial = emptyMap())
    var strengthOpen by remember { mutableStateOf(false) }
    var liveStrength by remember { mutableIntStateOf(100) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewMissing by remember { mutableStateOf(false) }
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
    // Het kader waarin de pager staat en het begin van dit scherm, beide in schermcoördinaten, om de foto naar zijn tegel te laten vliegen.
    var pagerRect by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var hero by remember { mutableStateOf<Hero?>(null) }
    var heroT by remember { mutableFloatStateOf(0f) }
    // Openen: de foto groeit uit zijn tegel; tot dat klaar is staat de pager uit en komt de achtergrond (openT) geleidelijk binnen.
    var opening by remember { mutableStateOf(true) }
    var openT by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pager.currentPage) { zoomed = false; strengthOpen = false }
    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]
    val keepNow = keepByName[current.item.name] ?: keepHdr
    val hdrPreview = keepNow && hdrPreviewSupported
    // Een scherm laat HDR alleen zien als het venster in HDR-modus staat. Dat gaat aan zolang het HDR-voorbeeld zichtbaar is en daarna weer terug,
    // want in HDR-modus kan de rest van het scherm er anders uitzien.
    val hdrShown = strengthOpen && hdrPreview && current.hdr
    val window = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity }?.window }
    DisposableEffect(hdrShown, window) {
        val before = window?.colorMode
        if (hdrShown && window != null) window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
        onDispose { if (hdrShown && window != null && before != null) window.colorMode = before }
    }
    val currentId by rememberUpdatedState(current.item.id)
    // Tijdens het openen meldt het openen zelf de foto, op het moment dat de tegel leeg moet.
    LaunchedEffect(current.item.id) { if (!opening) onCurrent(current.item.id) }
    val scanner = context.graph.scanner
    var askEdited by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Bewaren en niet bewaren gaan door naar de volgende foto. De laatste blijft staan.
    fun next() { if (pager.currentPage < list.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
    LaunchedEffect(Unit) {
        snapshotFlow { pagerRect }.first { it.width > 0f && it.height > 0f }
        val start = list[pager.currentPage.coerceIn(0, list.lastIndex)]
        val bitmap = scanner.cachedLarge(start.item, PREVIEW_EDGE) ?: scanner.cached(start.item, 360)
        val tile = bounds.rect(start.item.id)
        if (bitmap != null && tile != null) {
            // De foto groeit vanuit zijn eigen tegel naar zijn plaats; de tegel blijft vanaf nu leeg.
            val fit = minOf(pagerRect.width / bitmap.width, pagerRect.height / bitmap.height)
            val w = bitmap.width * fit
            val h = bitmap.height * fit
            val centre = pagerRect.center
            heroT = 0f
            hero = Hero(bitmap, tile, Rect(centre.x - w / 2f, centre.y - h / 2f, centre.x + w / 2f, centre.y + h / 2f))
            onCurrent(start.item.id)
            coroutineScope {
                launch { animate(0f, 1f, animationSpec = tween(HERO_MS)) { value, _ -> openT = value } }
                animate(0f, 1f, animationSpec = tween(HERO_MS, easing = FastOutSlowInEasing)) { value, _ -> heroT = value }
            }
            hero = null
        } else {
            // Geen tegel in beeld: gewoon invloeien.
            opening = false
            animate(0f, 1f, animationSpec = tween(180)) { value, _ -> openT = value }
        }
        opening = false
        onCurrent(currentId)
    }
    fun closeNow(velocity: Velocity) {
        if (closing || opening) return
        closing = true
        val bitmap = scanner.cachedLarge(current.item, PREVIEW_EDGE) ?: scanner.cached(current.item, 360)
        val tile = bounds.rect(current.item.id)
        if (bitmap != null && tile != null && !zoomed && pagerRect.width > 0f && pagerRect.height > 0f) {
            // Zoals Immich: de foto vliegt vanaf waar hij nu is terug naar zijn eigen tegel, die tot nu toe leeg stond.
            val fit = minOf(pagerRect.width / bitmap.width, pagerRect.height / bitmap.height)
            val scale = 1f - (1f - MIN_DRAG_SCALE) * progress()
            val centre = pagerRect.center + drag
            val w = bitmap.width * fit * scale
            val h = bitmap.height * fit * scale
            heroT = 0f
            hero = Hero(bitmap, Rect(centre.x - w / 2f, centre.y - h / 2f, centre.x + w / 2f, centre.y + h / 2f), tile)
            settleJob[0] = scope.launch {
                launch { animate(closeFade, 1f, animationSpec = tween(HERO_MS)) { value, _ -> closeFade = value } }
                animate(0f, 1f, animationSpec = tween(HERO_MS, easing = FastOutSlowInEasing)) { value, _ -> heroT = value }
                // De tegel verschijnt nu onder de foto, die daarna wegvervaagt: geen sprong.
                onClose()
            }
        } else {
            onClose()
            // Geen tegel in beeld: het scherm wisselt al, de foto zakt nog even door terwijl hij met het scherm wegvervaagt.
            settleJob[0] = scope.launch {
                launch { animate(closeFade, 1f, animationSpec = tween(240)) { value, _ -> closeFade = value } }
                animate(Offset.VectorConverter, drag, drag + Offset(0f, areaHeight * 0.2f), Offset(velocity.x, velocity.y), tween(240, easing = LinearOutSlowInEasing)) { value, _ -> drag = value }
            }
        }
    }
    // Het gebaar hieronder leeft langer dan één samenstelling; het moet de huidige foto en grenzen zien, niet die van het begin.
    val closeLatest by rememberUpdatedState<(Velocity) -> Unit> { closeNow(it) }
    // Terug (knop of gebaar) sluit op dezelfde manier. Altijd actief, zodat het scherm niet midden in de vlucht wegvalt.
    BackHandler { closeLatest(Velocity.Zero) }
    // Staat het sterktepaneel open, dan sluit terug eerst dat.
    BackHandler(enabled = strengthOpen) { strengthOpen = false }
    // Knoppen en kop zijn al weg na een vijfde van het sleepbereik, zodat ze niet over de galerij eronder hangen.
    // Bij het openen komen ze pas binnen als de foto bijna op zijn plek is.
    val barsFade: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = { alpha = ((openT - 0.5f) * 2f).coerceIn(0f, 1f) * (1f - (progress() * BARS_FADE).coerceAtMost(1f)) }

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // De achtergrond wordt doorzichtig naarmate je de foto wegsleept: de galerij eronder komt langzaam terug.
            .drawBehind { drawRect(backdrop, alpha = openT * (1f - (progress() * BACKDROP_FADE).coerceAtMost(1f))) }
            // De viewer ligt over de galerij: aanrakingen mogen er niet doorheen vallen.
            .pointerInput(Unit) {},
    ) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().graphicsLayer(block = barsFade).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            KeeperIconButton(KeeperIcons.Back, stringResource(R.string.settings_back), { closeLatest(Velocity.Zero) })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(current.item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(R.string.viewer_progress, pager.currentPage + 1, list.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.item.isFavorite) Icon(KeeperIcons.Favorite, stringResource(R.string.filter_favorites), tint = Color(0xFFFF8FA3), modifier = Modifier.padding(end = 14.dp).size(20.dp))
            // Lightroom staat in de kop: een knop onderin liet de rij met bewaren en prullenbak bij elke RAW omhoog springen.
            if (current.item.isDng) Row(
                Modifier.padding(end = 8.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .bouncyClickable { openInLightroom(scope, context, current, photos, defaultLook) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KeeperIcons.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.review_lightroom), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            }
            // Het menu staat er altijd, ook bij een foto die al klaar is: daar maakt "nieuwe versie maken" een tweede naast de eerste.
            Box {
                KeeperIconButton(KeeperIcons.More, stringResource(R.string.viewer_more), { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val asked = current
                    DropdownMenuItem(
                        text = { Text(stringResource(if (asked.status == Status.Done) R.string.viewer_convert_again else R.string.viewer_convert_one)) },
                        // Alleen wat HDR-naar-SDR kan: een JPG met gain map of de camera-versie van een RAW.
                        enabled = !converting && (asked.hdr || asked.item.isDng),
                        onClick = { menuOpen = false; onConvertOne(asked) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_hdr_options)) },
                        // Alleen een HDR-JPG heeft een gain map om te schuiven.
                        enabled = asked.hdr,
                        onClick = { menuOpen = false; liveStrength = strengthByName[asked.item.name] ?: defaultStrength; strengthOpen = true },
                    )
                }
            }
        }
        // De vaste omlijsting vangt het gebaar op (in de Initial-ronde, vóór de pager); de pager erbinnen krijgt de verplaatsing.
        // Zo staan de aanraakcoördinaten niet mee te schuiven met de foto.
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { areaHeight = it.size.height.toFloat().coerceAtLeast(1f); pagerRect = it.boundsInRoot() }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (zoomed || closing || opening) return@awaitEachGesture
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
                        if (drag.y > areaHeight * CLOSE_AT || (velocity.y > CLOSE_VELOCITY && drag.y > slop * 2)) closeLatest(velocity) else springBack(velocity)
                    }
                },
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val scale = 1f - (1f - MIN_DRAG_SCALE) * progress()
                    translationX = drag.x; translationY = drag.y; scaleX = scale; scaleY = scale
                    // Tijdens de vlucht naar de tegel tekent de laag hieronder de foto; deze staat dan even uit.
                    alpha = if (hero != null || opening) 0f else 1f
                },
                userScrollEnabled = !zoomed,
                key = { list.getOrNull(it)?.item?.id ?: it },
            ) { page ->
                val photo = list.getOrNull(page)
                if (photo != null) ZoomablePhoto(
                    photo, active = page == pager.currentPage, onZoomed = { zoomed = it },
                    strengthPreview = if (strengthOpen && page == pager.currentPage && photo.hdr) liveStrength else null,
                    hdrPreview = hdrPreview,
                    onPreviewBusy = { previewBusy = it },
                    onPreviewMissing = { previewMissing = it },
                )
            }
        }
        if (strengthOpen) HdrStrengthPanel(
            percent = liveStrength,
            defaultPercent = defaultStrength,
            keepHdr = keepNow,
            onKeepChange = { keep -> val asked = current; scope.launch { strengthPrefs.setKeepHdrFor(asked.item.name, keep) } },
            hasOverride = strengthByName.containsKey(current.item.name) || liveStrength != defaultStrength || keepByName.containsKey(current.item.name),
            converting = converting,
            loading = previewBusy,
            missing = previewMissing && !previewBusy,
            onChange = { liveStrength = it },
            onFinished = { val asked = current; scope.launch { strengthPrefs.setHdrStrengthFor(asked.item.name, liveStrength) } },
            onReset = { val asked = current; liveStrength = defaultStrength; scope.launch { strengthPrefs.setHdrStrengthFor(asked.item.name, null); strengthPrefs.setKeepHdrFor(asked.item.name, null) } },
            onDone = { strengthOpen = false },
            // Eerst opslaan: de omzetting leest de sterkte van deze foto bij het begin.
            onConvert = { val asked = current; strengthOpen = false; scope.launch { strengthPrefs.setHdrStrengthFor(asked.item.name, liveStrength); onConvertOne(asked) } },
            modifier = Modifier.graphicsLayer(block = barsFade).padding(horizontal = 12.dp, vertical = 8.dp),
        ) else Column(Modifier.graphicsLayer(block = barsFade).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(current.status.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${formatMegapixels(current.item.megapixels)}  ·  ${formatBytes(current.item.size)}  ·  ${formatDay(current.takenMs)} ${formatTime(current.takenMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Bewaren en niet bewaren zetten de keuze en gaan door naar de volgende (de laatste foto blijft staan). Nog een keer op
            // dezelfde knop tikken draait de keuze terug en blijft op deze foto.
            // IntrinsicSize.Min: de prullenbak is precies zo hoog als de pillen ernaast, ook bij een ander lettertype.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current.selectable) {
                    // Verbonden knoppen zoals in Material 3 Expressive: een haartje uit elkaar, buitenkant helemaal rond, binnenkant klein.
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ConnectedGap)) {
                        if (current.discarded) PrimaryPillButton(
                            stringResource(R.string.viewer_discarded), { onUndoReject(current) }, Modifier.weight(1f), icon = KeeperIcons.Close,
                            container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer, shape = ConnectedStartShape,
                        ) else SecondaryPillButton(stringResource(R.string.viewer_discard), { onReject(current); next() }, Modifier.weight(1f), icon = KeeperIcons.Close, shape = ConnectedStartShape)
                        if (current.selected) PrimaryPillButton(stringResource(R.string.viewer_kept), { onSelect(current, false) }, Modifier.weight(1f), icon = KeeperIcons.Check, shape = ConnectedEndShape)
                        else SecondaryPillButton(stringResource(R.string.viewer_keep), { if (current.askBeforeKeeping) askEdited = true else { onSelect(current, true); next() } }, Modifier.weight(1f), icon = KeeperIcons.Check, shape = ConnectedEndShape)
                    }
                } else Box(Modifier.weight(1f))
                // Naar de prullenbak, na Android's eigen bevestiging; terug te zetten.
                KeeperIconButton(
                    KeeperIcons.Delete, stringResource(R.string.review_trash), { onTrash(current) },
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
                    size = 48, iconSize = 22, background = MaterialTheme.colorScheme.errorContainer, tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
    if (askEdited) {
        // De vraag hoort bij de foto waarop je drukte; blijf je niet op die foto, dan geldt hij nog steeds voor die ene.
        val asked = current
        EditedKeepDialog(
            1,
            onEditedOnly = { askEdited = false; onReject(asked); next() },
            // Alleen het origineel: de bewerking (Lightroom-export of Galerij-kopie) gaat niet mee en blijft waar hij staat.
            onOriginalOnly = {
                askEdited = false
                onSelect(asked, true)
                photos.filter { it.item.id in asked.editIds }.forEach(onReject)
                next()
            },
            onBoth = { askEdited = false; onSelect(asked, true); next() },
            onDismiss = { askEdited = false },
        )
    }
    hero?.let { h ->
        // De foto groeit of krimpt van zijn plek in de viewer naar het vierkantje van de tegel, en snijdt zich daarbij bij tot dat vierkant.
        val bitmap = remember(h) { h.bitmap.asImageBitmap() }
        Image(
            bitmap, null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val rect = lerp(h.from, h.to, heroT)
                    val placeable = measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((rect.left - origin.x).roundToInt(), (rect.top - origin.y).roundToInt())
                    }
                }
                .graphicsLayer { shape = RoundedCornerShape(TILE_CORNER.toPx() * heroT); clip = true },
        )
    }
    }
}

/** Vanaf waar de foto naar zijn tegel vliegt, en waarnaartoe; beide in schermcoördinaten. */
private class Hero(val bitmap: android.graphics.Bitmap, val from: Rect, val to: Rect)

/**
 * Het HDR-voorbeeld van HDR behouden: de foto één keer door Android laten decoderen (verkleind, met gain map, zie [HdrPreview]) en bij elke stap van
 * [strength] (0 tot 100) een bitmap met de gain map op die sterkte. Tot de eerste klaar is, blijft het gewone beeld staan (null).
 */
@androidx.annotation.RequiresApi(34)
@Composable
private fun hdrLivePreview(id: Long, strength: Int?, onBusy: (Boolean) -> Unit, onMissing: (Boolean) -> Unit): ImageBitmap? {
    val context = LocalContext.current
    val session: HdrPreview? by produceState<HdrPreview?>(null, id) {
        onBusy(true)
        onMissing(false)
        value = withContext(Dispatchers.IO) { HdrPreview.open(context.contentResolver, MediaScanner.imageUri(id)) }
        onMissing(value == null)
        onBusy(false)
    }
    val live: ImageBitmap? by produceState<ImageBitmap?>(null, session, strength) {
        val s = session
        if (s == null || strength == null) { value = null; return@produceState }
        value = withContext(Dispatchers.Default) { s.render(strength / 100f) }.asImageBitmap()
    }
    return live
}

/**
 * Een foto die je kunt inzoomen. Eerst staat er een snel voorbeeld; zodra je inzoomt komt er een scherper beeld
 * (tot [DETAIL_EDGE] pixels) voor in de plaats, om de kwaliteit te beoordelen. Met één vinger veegt de pager door;
 * pas als er is ingezoomd, of met twee vingers, nemen we het gebaar over.
 */
@Composable
private fun ZoomablePhoto(
    photo: Photo, active: Boolean, onZoomed: (Boolean) -> Unit,
    /** Is dit gezet (0 tot 100), dan toont de foto wat de omzetting bij die HDR-sterkte zou maken, en beweegt mee als het getal verandert. */
    strengthPreview: Int? = null,
    /** Met HDR behouden: het voorbeeld is de foto met zijn gain map (door Android getekend) in plaats van de SDR-omzetting. */
    hdrPreview: Boolean = false,
    onPreviewBusy: (Boolean) -> Unit = {},
    /** Het voorbeeld is er niet (de foto is niet te lezen als Ultra HDR); het paneel zegt dat. */
    onPreviewMissing: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scanner = context.graph.scanner
    val tone by context.graph.prefs.toneMap.collectAsState(initial = "normaal")
    val previewing = strengthPreview != null
    // Het voorbeeld wordt één keer gedecodeerd (verkleind) zodra het paneel opengaat; elke stap van de schuif rekent daarna alleen de tone map opnieuw.
    val sdrPreviewing = previewing && !hdrPreview
    val session: ConvertPreview? by produceState<ConvertPreview?>(null, photo.item.id, sdrPreviewing) {
        value = null
        if (sdrPreviewing) {
            onPreviewBusy(true)
            onPreviewMissing(false)
            value = withContext(Dispatchers.IO) {
                ConvertPreview.open(JpegSource { context.contentResolver.openFileDescriptor(MediaScanner.imageUri(photo.item.id), "r") ?: throw java.io.IOException("niet te openen") })
            }
            onPreviewMissing(value == null)
            onPreviewBusy(false)
        }
    }
    val sdrLive: ImageBitmap? by produceState<ImageBitmap?>(null, session, strengthPreview, tone) {
        val s = session
        val strength = strengthPreview
        if (s == null || strength == null) { value = null; if (strength == null) onPreviewBusy(false); return@produceState }
        onPreviewBusy(true)
        // Een nieuwe stap annuleert deze berekening (de sleutels veranderen): kort wachten voorkomt werk dat meteen wordt weggegooid.
        delay(30)
        value = s.render(ConvertSettings(90, ToneMap.of(tone), true, strength / 100f)).asImageBitmap()
        onPreviewBusy(false)
    }
    val live = if (hdrPreview && previewing && Build.VERSION.SDK_INT >= 34) hdrLivePreview(photo.item.id, strengthPreview, onPreviewBusy, onPreviewMissing) else sdrLive
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val zoomScope = rememberCoroutineScope()
    // Dubbeltikken in- of uitzoomen loopt als animatie; een vinger op het scherm breekt hem af en neemt het over.
    var zoomJob by remember { mutableStateOf<Job?>(null) }
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
                    // Uitzoomen gaat terug naar het geheel; inzoomen houdt het getikte punt onder je vinger.
                    val toScale: Float
                    val toOffset: Offset
                    if (scale > 1.05f) { toScale = 1f; toOffset = Offset.Zero }
                    else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        toScale = DOUBLE_TAP_ZOOM
                        toOffset = clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                    }
                    val fromScale = scale
                    val fromOffset = offset
                    zoomJob?.cancel()
                    zoomJob = zoomScope.launch {
                        animate(0f, 1f, animationSpec = tween(ZOOM_MS, easing = KeeperMotion.Standard)) { t, _ ->
                            scale = fromScale + (toScale - fromScale) * t
                            offset = Offset(fromOffset.x + (toOffset.x - fromOffset.x) * t, fromOffset.y + (toOffset.y - fromOffset.y) * t)
                        }
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
                            // Beweegt een vinger tijdens het in- of uitzoomen, dan stopt de animatie waar hij is en neem jij het over. Alleen
                            // aanraken niet: de tweede tik van het dubbeltikken zelf mag de animatie die hij net start niet afbreken.
                            if (zoom != 1f || pan != Offset.Zero) zoomJob?.cancel()
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
        (live ?: detail ?: preview)?.let {
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
