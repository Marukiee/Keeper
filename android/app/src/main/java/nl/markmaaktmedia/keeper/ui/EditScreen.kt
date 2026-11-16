package nl.markmaaktmedia.keeper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.Photo
import nl.markmaaktmedia.keeper.scan.Status
import nl.markmaaktmedia.keeper.ui.components.KeeperIconButton
import nl.markmaaktmedia.keeper.ui.components.EmptyState
import nl.markmaaktmedia.keeper.ui.components.GroupedRow
import nl.markmaaktmedia.keeper.ui.components.PrimaryPillButton
import nl.markmaaktmedia.keeper.ui.components.SecondaryPillButton
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons

private const val LIGHTROOM = "com.adobe.lrmobile"

@Composable
fun EditScreen(photos: List<Photo>, onCameraVersion: (Photo, Boolean) -> Unit, onTrash: (Photo) -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val list = photos.filter { it.status == Status.NeedsEdit || it.status == Status.CameraVersion }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryPillButton(stringResource(R.string.edit_open), { openInLightroom(context, photo) }, Modifier.weight(1f), icon = KeeperIcons.OpenInNew)
                        if (chosen) SecondaryPillButton(stringResource(R.string.edit_camera_undo), { onCameraVersion(photo, false) }, Modifier.weight(1f))
                        else PrimaryPillButton(stringResource(R.string.edit_camera), { onCameraVersion(photo, true) }, Modifier.weight(1f), icon = KeeperIcons.Camera)
                    }
                }
            }
        }
    }
}

/** Opent de RAW in Lightroom; zonder Lightroom laat Android een app kiezen. */
internal fun openInLightroom(context: Context, photo: Photo) {
    val uri = MediaScanner.imageUri(photo.item.id)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, photo.item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent(view).setPackage(LIGHTROOM))
    } catch (_: ActivityNotFoundException) {
        try {
            Toast.makeText(context, R.string.edit_no_lightroom, Toast.LENGTH_SHORT).show()
            context.startActivity(Intent.createChooser(view, null))
        } catch (_: ActivityNotFoundException) { }
    }
}
