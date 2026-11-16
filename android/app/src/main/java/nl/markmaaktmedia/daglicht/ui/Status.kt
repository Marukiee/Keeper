package nl.markmaaktmedia.daglicht.ui

import androidx.annotation.StringRes
import nl.markmaaktmedia.daglicht.R
import nl.markmaaktmedia.daglicht.scan.Status

@get:StringRes
val Status.label: Int
    get() = when (this) {
        Status.Convert -> R.string.status_convert
        Status.AsIs -> R.string.status_asis
        Status.LightroomExport -> R.string.status_lightroom
        Status.GalleryEdit -> R.string.status_gallery
        Status.NeedsEdit -> R.string.status_needs_edit
        Status.CameraVersion -> R.string.status_camera
        Status.RawEdited -> R.string.status_raw_edited
        Status.OriginalReplaced -> R.string.status_replaced
        Status.OriginalExported -> R.string.status_original_exported
        Status.NotKept -> R.string.status_not_kept
        Status.Skipped -> R.string.status_skipped
        Status.Done -> R.string.status_done
    }
