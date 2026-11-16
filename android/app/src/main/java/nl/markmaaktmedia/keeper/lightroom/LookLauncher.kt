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

    /**
     * Uitkomst van het raden. [preset] is de look om te gebruiken (null: geen). [oppoName] is wat de JPG's zeiden (leeg: geen preset), null als dat
     * niet te zeggen was; dan geldt de standaard uit Instellingen. [missing] is de naam van een preset die de JPG's noemen maar waar Keeper nog geen look voor heeft.
     */
    class Guess(val preset: LookPreset?, val oppoName: String?, val missing: String? = null)

    /** Welke preset hoort bij deze RAW? De naam staat niet in een DNG, dus kijken we naar de camera-JPG's die er kort naast zijn genomen. */
    suspend fun guess(context: Context, photo: Photo, all: List<Photo>): Guess = withContext(Dispatchers.IO) {
        val t = photo.takenMs
        val neighbours = all.asSequence()
            .filter { it.item.isJpeg && abs(it.takenMs - t) <= PresetGuess.WINDOW_MS }
            .sortedBy { abs(it.takenMs - t) }
            .take(6)
            .mapNotNull { jpeg -> presetNameOf(context, jpeg)?.let { PresetGuess.Neighbour(jpeg.takenMs, it.ifEmpty { null }) } }
            .toList()
        val name = PresetGuess.decide(t, neighbours) ?: return@withContext Guess(null, null)
        if (name.isEmpty()) return@withContext Guess(null, "")
        val preset = LookPreset.fromOppo(name)
        if (preset != null && preset in LookPreset.available(context)) Guess(preset, name) else Guess(null, name, missing = name)
    }

    /** De presetnaam uit de trailer van [jpeg], "" als er geen preset was, null als het bestand niet te lezen is. */
    private fun presetNameOf(context: Context, jpeg: Photo): String? = try {
        context.contentResolver.openFileDescriptor(MediaScanner.imageUri(jpeg.item.id), "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { OppoTrailer.presetName(it.channel).orEmpty() }
        }
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
