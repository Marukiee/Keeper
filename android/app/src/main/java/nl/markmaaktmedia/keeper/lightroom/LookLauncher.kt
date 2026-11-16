package nl.markmaaktmedia.keeper.lightroom

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.Photo

/**
 * Opent een RAW in Lightroom met de preset van de camera er al overheen. Lightroom mobile leest de ontwikkelinstellingen uit de XMP van de DNG
 * (gemeten: een `crs:Look` met `crs:Parameters` en een RGB-tabel werkt, losse tabelattributen niet). We maken een kopie in de cache met die XMP
 * erbij en laten Lightroom die kopie openen; het origineel in DCIM blijft ongemoeid. De naam van de kopie blijft gelijk aan het origineel, want
 * Keeper herkent een Lightroom-export aan die bestandsnaam.
 */
object LookLauncher {
    private const val LIGHTROOM = "com.adobe.lrmobile"
    private const val DNG_MIME = "image/x-adobe-dng"
    private const val NEIGHBOUR_MS = 3 * 60_000L
    private const val MAX_COPIES = 3

    /** Welke preset hoort bij deze RAW? De naam staat niet in een DNG, dus kijken we naar de camera-JPG die er kort naast is genomen. */
    suspend fun guess(context: Context, photo: Photo, all: List<Photo>): LookPreset? = withContext(Dispatchers.IO) {
        val t = photo.takenMs
        all.asSequence()
            .filter { it.item.isJpeg && abs(it.takenMs - t) <= NEIGHBOUR_MS }
            .sortedBy { abs(it.takenMs - t) }
            .take(3)
            .mapNotNull { presetOf(context, it) }
            .firstOrNull()
    }

    private fun presetOf(context: Context, jpeg: Photo): LookPreset? = try {
        context.contentResolver.openFileDescriptor(MediaScanner.imageUri(jpeg.item.id), "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { OppoTrailer.presetName(it.channel) }
        }?.let { LookPreset.fromOppo(it) }
    } catch (_: Exception) { null }

    /** Opent [photo] in Lightroom met [preset]; zonder preset (of als het misgaat) het origineel, zoals voorheen. */
    suspend fun open(context: Context, photo: Photo, preset: LookPreset?) {
        val uri: Uri? = if (preset == null || !photo.item.isDng) null else withContext(Dispatchers.IO) { prepare(context, photo, preset) }
        val data = uri ?: MediaScanner.imageUri(photo.item.id)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(data, if (uri != null) DNG_MIME else photo.item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent(view).setPackage(LIGHTROOM))
        } catch (_: ActivityNotFoundException) {
            try {
                Toast.makeText(context, R.string.edit_no_lightroom, Toast.LENGTH_SHORT).show()
                context.startActivity(Intent.createChooser(view, null))
            } catch (_: ActivityNotFoundException) { }
        }
    }

    /** Kopie met de Look erin, als content-URI voor Lightroom. Null als het niet lukt (dan gaat het origineel open). */
    private fun prepare(context: Context, photo: Photo, preset: LookPreset): Uri? = try {
        val dir = File(context.cacheDir, "looks").apply { mkdirs() }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_COPIES - 1)?.forEach { it.delete() }
        val file = File(dir, photo.item.name)
        context.contentResolver.openInputStream(MediaScanner.imageUri(photo.item.id))!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val head = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (!DngXmp.isTiff(head)) null else {
            val description = context.assets.open("looks/${preset.id}.xml").use { it.readBytes().toString(Charsets.UTF_8) }
            DngXmp.patch(file, description)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    } catch (_: Exception) { null }
}
