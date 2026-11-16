package nl.markmaaktmedia.keeper.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.keeper.data.KeeperPrefs

/** Wat er op de telefoon staat, zonder de keuzes van Mark. Die komen er los overheen (zie [Classifier]). */
data class Snapshot(
    val items: List<MediaItem>,
    val probes: Map<Long, Probe>,
    val doneNames: Set<String>,
    val lightroomFolder: String,
    val sinceMs: Long,
)

/** Alleen lezen: MediaStore en de kop van elke JPEG. Er wordt niets geschreven, gewijzigd of verwijderd. */
class MediaScanner(private val context: Context, private val prefs: KeeperPrefs) {
    private val files: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    suspend fun scan(): Snapshot = withContext(Dispatchers.IO) {
        val since = prefs.sinceMs.first()
        val lightroom = prefs.lightroomFolder.first()
        val output = prefs.outputFolder.first()
        val items = query(since, lightroom, prefs.includeCshot.first())
        val probes = items
            .filter { it.isJpeg && !Classifier.isIgnored(it) && !it.relativePath.startsWith(lightroom, ignoreCase = true) && !Classifier.isGalleryEdit(it.name) }
            .associate { it.id to probe(it) }
        Snapshot(items, probes, doneNames(output), lightroom, since)
    }

    private fun query(sinceMs: Long, lightroom: String, includeCshot: Boolean): List<MediaItem> {
        val selection = buildString {
            append("${C.MIME_TYPE} LIKE 'image/%' AND ${C.DATE_ADDED} >= ? AND (${C.RELATIVE_PATH} LIKE ? OR ${C.RELATIVE_PATH} LIKE ?)")
            if (!includeCshot) append(" AND ${C.RELATIVE_PATH} NOT LIKE 'DCIM/Camera/Cshot%'")
        }
        val args = arrayOf((sinceMs / 1000).toString(), "DCIM/Camera/%", "$lightroom%")
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(files, PROJECTION, selection, args, "${C.DATE_TAKEN} DESC")?.use { cur ->
            while (cur.moveToNext()) {
                out += MediaItem(
                    id = cur.getLong(0), name = cur.getString(1).orEmpty(), relativePath = cur.getString(2).orEmpty(),
                    size = cur.getLong(3), dateAddedMs = cur.getLong(4) * 1000, dateTakenMs = cur.getLong(5),
                    width = cur.getInt(6), height = cur.getInt(7), mime = cur.getString(8).orEmpty(), isFavorite = cur.getInt(9) == 1,
                )
            }
        }
        return out
    }

    /** Wat al in de uitvoermap staat, zodat een foto niet twee keer klaargemaakt wordt. */
    private fun doneNames(output: String): Set<String> {
        val out = HashSet<String>()
        context.contentResolver.query(files, arrayOf(C.DISPLAY_NAME), "${C.RELATIVE_PATH} LIKE ?", arrayOf("$output%"), null)?.use {
            while (it.moveToNext()) it.getString(0)?.let(out::add)
        }
        return out
    }

    private fun probe(item: MediaItem): Probe =
        runCatching { context.contentResolver.openInputStream(imageUri(item.id))?.use { UltraHdrProbe.probe(it, item.size) } }
            .getOrNull() ?: Probe(hasGainMap = false)

    /** Een voorbeeld voor in de lijst. DNG's geven hun ingebedde preview. Null als Android er geen kan maken. */
    suspend fun thumbnail(item: MediaItem, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        // Een DNG gaat door dezelfde decoder als de viewer, anders toont de tegel Android's miniatuur zonder het camerafilter
        // terwijl de foto groot er wel mee staat. Voor een JPG is Android's miniatuur snel en gelijk.
        cached(item, edge) ?: ((if (item.isDng) decode(item, edge) else null) ?: system(item, edge))?.also { cache.put("${item.id}:$edge", it) }
    }

    private fun system(item: MediaItem, edge: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(imageUri(item.id), Size(edge, edge), null) }.getOrNull()

    /** Een groot beeld voor het bekijken: scherper dan Android's eigen miniatuur, en bewaard voor de volgende keer. */
    suspend fun large(item: MediaItem, edge: Int): Bitmap? {
        cache.get("${item.id}:L$edge")?.let { return it }
        return detail(item, edge)?.also { cache.put("${item.id}:L$edge", it) }
    }

    fun cachedLarge(item: MediaItem, edge: Int): Bitmap? = cache.get("${item.id}:L$edge")

    /** Wat al eerder is gemaakt, zodat terugscrollen in de galerij niet opnieuw laadt. */
    fun cached(item: MediaItem, edge: Int): Bitmap? = cache.get("${item.id}:$edge")

    /**
     * Een scherp beeld om in te zoomen: tot [maxEdge] pixels op de lange kant, zonder HDR (software-bitmap, de gain map blijft ongebruikt).
     * Voor een RAW geeft Android hier zijn eigen weergave; die kan afwijken van de camera-versie.
     */
    suspend fun detail(item: MediaItem, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        decode(item, maxEdge) ?: thumbnail(item, maxEdge)
    }

    /** ImageDecoder op het bestand zelf, tot [maxEdge] pixels op de lange kant. Null als Android het niet kan lezen. */
    private fun decode(item: MediaItem, maxEdge: Int): Bitmap? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, imageUri(item.id))
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge) decoder.setTargetSampleSize((longest + maxEdge - 1) / maxEdge)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(96 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    companion object {
        private val PROJECTION = arrayOf(
            "_id", C.DISPLAY_NAME, C.RELATIVE_PATH, C.SIZE, C.DATE_ADDED, C.DATE_TAKEN, C.WIDTH, C.HEIGHT, C.MIME_TYPE, C.IS_FAVORITE,
        )

        fun imageUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }
}
