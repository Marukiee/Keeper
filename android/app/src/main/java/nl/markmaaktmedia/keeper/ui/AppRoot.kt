package nl.markmaaktmedia.keeper.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import nl.markmaaktmedia.keeper.convert.ConvertOptions
import nl.markmaaktmedia.keeper.convert.ConvertState
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.MediaStore
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.Photo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.markmaaktmedia.keeper.MainViewModel
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.settings.SettingsPage
import nl.markmaaktmedia.keeper.ui.components.PillNavItem
import nl.markmaaktmedia.keeper.ui.components.PillNavigationBar
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons

private enum class Sub { None, Viewer, Overview, Page }

/** De foto's die het groot-bekijken-scherm doorloopt, vast bij het openen. */
private data class ViewerRequest(val ids: List<Long>, val startId: Long)

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf(Sub.None) }
    // De instellingenpagina die open staat als sub Page is.
    var page by remember { mutableStateOf(SettingsPage.Look) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    // De foto die de viewer nu toont, en waar de tegels staan: voor de vlucht van de foto terug naar zijn tegel.
    var viewerCurrent by remember { mutableStateOf<Long?>(null) }
    val tileBounds = remember { TileBounds() }
    var permitted by remember { mutableStateOf(granted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }

    // Verwijderen is altijd naar de prullenbak en altijd met Android's eigen bevestiging; Keeper wist nooit zelf iets.
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.refresh()
    }
    val trash: (List<Photo>) -> Unit = { photos ->
        if (photos.isNotEmpty()) {
            val uris = photos.map { MediaScanner.imageUri(it.item.id) }
            val request = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    // Omzetten: de locatie van de foto's en meldingen (voortgang, verslag) vragen we vlak voor de eerste run. Zonder locatie begint hij niet,
    // want dan zou Android de GPS-gegevens uit elk bestand halen en kwamen ze niet in de uitvoer.
    val converter = context.graph.converter
    val convertState by converter.state.collectAsState()
    val outputFolder by context.graph.prefs.outputFolder.collectAsState(initial = "")
    val hasLocation = { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED }
    // Wat er na het vragen van de toestemming gestart moet worden: alles wat meegaat, of alleen één foto.
    var pendingConvert by remember { mutableStateOf(ConvertOptions()) }
    val convertPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) converter.start(pendingConvert) else Toast.makeText(context, R.string.convert_need_location, Toast.LENGTH_LONG).show()
    }
    val beginConvert: (ConvertOptions) -> Unit = { options ->
        val missing = buildList {
            if (!hasLocation()) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) converter.start(options) else { pendingConvert = options; convertPermissions.launch(missing.toTypedArray()) }
    }
    val startConvert: () -> Unit = { beginConvert(ConvertOptions()) }
    // Alleen deze foto, uit de viewer: de selectie blijft zoals ze is, en staat er al een versie (of dezelfde naam) dan komt er een tweede naast.
    val convertOne: (Photo) -> Unit = { photo -> beginConvert(ConvertOptions(only = setOf(photo.item.id), newVersion = true)) }
    val converting = convertState is ConvertState.Running
    // Na een pauze heet de knop Doorgaan; het onthouden zit in de instellingen, dus het geldt ook na het afsluiten van de app.
    val paused by context.graph.prefs.convertPaused.collectAsState(initial = false)
    // Zodra een run klaar is, staat er misschien van alles anders in de uitvoermap: opnieuw kijken.
    LaunchedEffect(convertState is ConvertState.Finished) { if (convertState is ConvertState.Finished && permitted) vm.refresh() }

    // Elke keer dat de app weer vooraan komt: opnieuw kijken, want er kunnen foto's bij zijn gekomen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = granted(context)
        if (permitted) vm.refresh()
    }
    LaunchedEffect(permitted) { if (permitted) vm.refresh() }
    LaunchedEffect(Unit) { context.graph.updater.checkIfDue() }

    // Uiterlijk hoort bij Uiterlijk en taal en gaat daarheen terug; elke andere pagina gaat terug naar het overzicht.
    val pageBack: () -> Unit = { if (page == SettingsPage.Appearance) page = SettingsPage.Look else sub = Sub.None }
    BackHandler(enabled = sub != Sub.None) { if (sub == Sub.Page) pageBack() else sub = Sub.None }

    val needsEdit = state.photos.count { it.status == Status.NeedsEdit }
    val items = listOf(
        PillNavItem(stringResource(R.string.nav_gallery), { KeeperIcons.Photos }),
        PillNavItem(stringResource(R.string.nav_edit), { KeeperIcons.Palette }, badgeCount = needsEdit),
        PillNavItem(stringResource(R.string.nav_settings), { KeeperIcons.Settings }, { KeeperIcons.SettingsFilled }),
    )
    val padding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // De galerij blijft onder de viewer staan (de viewer ligt er als laag overheen), zodat je hem tijdens het naar beneden
        // slepen langzaam terug ziet komen, en op dezelfde plek van de lijst staat als je terugkomt.
        AnimatedContent(
            targetState = Triple(if (sub == Sub.Viewer) Sub.None else sub, tab, if (sub == Sub.Page) page else null),
            transitionSpec = { (slideInVertically { it / 12 } + fadeIn()) togetherWith fadeOut() },
            label = "screen",
        ) { (currentSub, currentTab, currentPage) ->
            when (currentSub) {
                Sub.Viewer -> Unit
                Sub.Overview -> TodayScreen(state, permitted, { ask.launch(Manifest.permission.READ_MEDIA_IMAGES) }, { tab = 1; sub = Sub.None }, startConvert, converting, paused, { sub = Sub.None }, padding)
                Sub.Page -> SettingsPageScreen(currentPage ?: SettingsPage.Look, { page = it }, pageBack, padding)
                Sub.None -> when (currentTab) {
                    0 -> GalleryScreen(
                        state, GalleryFilter.entries[filter], { filter = it.ordinal }, permitted, { ask.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                        { photos -> vm.select(photos, true) },
                        { photos -> vm.reject(photos) },
                        { photo, shown -> viewer = ViewerRequest(shown.map { it.item.id }, photo.item.id); viewerCurrent = null; sub = Sub.Viewer },
                        { sub = Sub.Overview },
                        trash,
                        startConvert, converting, paused,
                        { vm.clearSelection() },
                        bounds = tileBounds,
                        // Zolang de viewer open staat blijft de tegel van de getoonde foto leeg.
                        hiddenId = if (sub == Sub.Viewer) viewerCurrent else null,
                    )
                    1 -> EditScreen(state.photos, { photo, use -> vm.useCameraVersion(photo, use) }, { photo -> trash(listOf(photo)) }, padding)
                    else -> SettingsScreen({ page = it; sub = Sub.Page }, padding)
                }
            }
        }
        ConvertDialog(convertState, outputFolder, { converter.requestPause() }, startConvert, { converter.acknowledge() })
        PillNavigationBar(
            items = items,
            selectedIndex = tab,
            onSelect = { tab = it; sub = Sub.None },
            // Zoals in Tandem: 20 dp van de zijkanten, niet tot de rand.
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        )
        // Boven alles, ook boven de navigatiebalk. De achtergrond van de viewer wordt doorzichtig bij het slepen.
        AnimatedVisibility(
            visible = sub == Sub.Viewer,
            // De viewer laat zichzelf binnenkomen: de foto groeit uit zijn tegel.
            enter = EnterTransition.None,
            exit = fadeOut(tween(220)),
        ) {
            viewer?.let { request ->
                ViewerScreen(
                    request.ids, request.startId, state.photos,
                    { photo, on -> vm.select(listOf(photo), on) }, { photo -> vm.reject(listOf(photo)) }, { photo -> trash(listOf(photo)) }, { sub = Sub.None },
                    onUndoReject = { photo -> vm.undoReject(listOf(photo)) },
                    bounds = tileBounds, onCurrent = { viewerCurrent = it },
                    onConvertOne = convertOne, converting = converting,
                )
            }
        }
    }
}

private fun granted(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
