package nl.markmaaktmedia.daglicht.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.MediaStore
import nl.markmaaktmedia.daglicht.scan.MediaScanner
import nl.markmaaktmedia.daglicht.scan.Photo
import androidx.compose.animation.AnimatedContent
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
import nl.markmaaktmedia.daglicht.MainViewModel
import nl.markmaaktmedia.daglicht.R
import nl.markmaaktmedia.daglicht.graph
import nl.markmaaktmedia.daglicht.scan.Status
import nl.markmaaktmedia.daglicht.ui.components.PillNavItem
import nl.markmaaktmedia.daglicht.ui.components.PillNavigationBar
import nl.markmaaktmedia.daglicht.ui.theme.DaglichtIcons

private enum class Sub { None, Viewer, Overview, Updates }

/** De foto's die het groot-bekijken-scherm doorloopt, vast bij het openen. */
private data class ViewerRequest(val ids: List<Long>, val startId: Long)

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf(Sub.None) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var permitted by remember { mutableStateOf(granted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }

    // Verwijderen is altijd naar de prullenbak en altijd met Android's eigen bevestiging; Daglicht wist nooit zelf iets.
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

    // Elke keer dat de app weer vooraan komt: opnieuw kijken, want er kunnen foto's bij zijn gekomen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = granted(context)
        if (permitted) vm.refresh()
    }
    LaunchedEffect(permitted) { if (permitted) vm.refresh() }
    LaunchedEffect(Unit) { context.graph.updater.checkIfDue() }

    BackHandler(enabled = sub != Sub.None) { sub = Sub.None }

    val needsEdit = state.photos.count { it.status == Status.NeedsEdit }
    val items = listOf(
        PillNavItem(stringResource(R.string.nav_gallery), { DaglichtIcons.Photos }),
        PillNavItem(stringResource(R.string.nav_edit), { DaglichtIcons.Tune }, { DaglichtIcons.TuneFilled }, badgeCount = needsEdit),
        PillNavItem(stringResource(R.string.nav_settings), { DaglichtIcons.Settings }, { DaglichtIcons.SettingsFilled }),
    )
    val padding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 112.dp)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        AnimatedContent(
            targetState = sub to tab,
            transitionSpec = { (slideInVertically { it / 12 } + fadeIn()) togetherWith fadeOut() },
            label = "screen",
        ) { (currentSub, currentTab) ->
            when (currentSub) {
                Sub.Viewer -> viewer?.let { request ->
                    ViewerScreen(request.ids, request.startId, state.photos, { photo, on -> vm.select(listOf(photo), on) }, { photo -> trash(listOf(photo)) }, { sub = Sub.None })
                }
                Sub.Overview -> TodayScreen(state, permitted, { ask.launch(Manifest.permission.READ_MEDIA_IMAGES) }, { tab = 1; sub = Sub.None }, { sub = Sub.None }, padding)
                Sub.Updates -> UpdatesScreen({ sub = Sub.None }, padding)
                Sub.None -> when (currentTab) {
                    0 -> GalleryScreen(
                        state, GalleryFilter.entries[filter], { filter = it.ordinal }, permitted, { ask.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                        { photos, on -> vm.select(photos, on) },
                        { photo, shown -> viewer = ViewerRequest(shown.map { it.item.id }, photo.item.id); sub = Sub.Viewer },
                        { sub = Sub.Overview },
                        trash,
                    )
                    1 -> EditScreen(state.photos, { photo, use -> vm.useCameraVersion(photo, use) }, { photo -> trash(listOf(photo)) }, padding)
                    else -> SettingsScreen({ sub = Sub.Updates }, padding)
                }
            }
        }
        if (sub != Sub.Viewer) {
            PillNavigationBar(
                items = items,
                selectedIndex = tab,
                onSelect = { tab = it; sub = Sub.None },
                // Zoals in Tandem: 20 dp van de zijkanten, niet tot de rand.
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private fun granted(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
