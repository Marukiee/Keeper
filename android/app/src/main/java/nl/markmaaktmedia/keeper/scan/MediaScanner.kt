package nl.markmaaktmedia.keeper.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import nl.markmaaktmedia.keeper.data.KeeperPrefs
import kotlin.coroutines.resume

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

    /** Wat de vorige scan opleverde en waarvoor: zolang MediaStore en de instellingen niet veranderd zijn, is die uitkomst nog goed. */
    private var last: Pair<ScanKey, Snapshot>? = null
    private val scanLock = Mutex()
    /** De kop van een JPEG verandert niet zolang id en grootte gelijk zijn, dus die wordt maar één keer gelezen. */
    private val probes = ConcurrentHashMap<Long, Pair<Long, Probe>>()

    private data class ScanKey(val generation: Long, val since: Long, val lightroom: String, val output: String, val cshot: Boolean)

    /**
     * De scan loopt maximaal één keer tegelijk en wordt overgeslagen als er sinds de vorige niets in MediaStore is veranderd (dat zegt de
     * generatiecijfer van het volume, een goedkope vraag). Voor de app weer vooraan komt, hoeft er dan geen enkel bestand opnieuw gelezen.
     */
    suspend fun scan(): Snapshot = scanLock.withLock {
        withContext(Dispatchers.IO) {
            val since = prefs.sinceMs.first()
            val lightroom = prefs.lightroomFolder.first()
            val output = prefs.outputFolder.first()
            val cshot = prefs.includeCshot.first()
            val generation = runCatching { MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL) }.getOrDefault(-1L)
            val key = ScanKey(generation, since, lightroom, output, cshot)
            last?.let { (was, snapshot) -> if (generation >= 0 && was == key) return@withContext snapshot }
            val items = query(since, lightroom, cshot)
            val probed = items
                .filter { it.isJpeg && !Classifier.isIgnored(it) && !it.relativePath.startsWith(lightroom, ignoreCase = true) && !Classifier.isGalleryEdit(it.name) }
                .associate { it.id to probe(it) }
            // Wat niet meer in de lijst staat, hoeft niet bewaard te blijven.
            probes.keys.retainAll(probed.keys)
            Snapshot(items, probed, doneNames(output), lightroom, since).also { last = key to it }
        }
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

    private fun probe(item: MediaItem): Probe {
        probes[item.id]?.let { (size, probe) -> if (size == item.size) return probe }
        val read = runCatching { context.contentResolver.openInputStream(imageUri(item.id))?.use { UltraHdrProbe.probe(it, item.size) } }.getOrNull()
        // Een mislukte lezing (bestand weg, geen rechten) wordt niet onthouden: de volgende scan probeert het opnieuw.
        return read?.also { probes[item.id] = item.size to it } ?: Probe(hasGainMap = false)
    }

    /** Een voorbeeld voor in de lijst. DNG's geven hun ingebedde preview. Null als Android er geen kan maken. */
    suspend fun thumbnail(item: MediaItem, edge: Int): Bitmap? {
        cached(item, edge)?.let { return it }
        // Hooguit een paar tegels tegelijk: snel scrollen zet anders tientallen decoders tegelijk aan het werk, voor tegels die al weer
        // buiten beeld zijn. Wie niet meer nodig is, komt hier niet eens aan de beurt (de aanroep is dan al geannuleerd).
        return withContext(decoders) {
            // Een DNG gaat door dezelfde decoder als de viewer, anders toont de tegel Android's miniatuur zonder het camerafilter
            // terwijl de foto groot er wel mee staat. Voor een JPG is Android's miniatuur snel en gelijk.
            // Die decode is duur (het hele RAW-bestand), daarom wordt het resultaat op schijf bewaard.
            val made = if (item.isDng) storedTile(item, edge) ?: decode(item, edge)?.also { storeTile(item, edge, it) } else null
            (made ?: system(item, edge))?.also { cache.put("${item.id}:$edge", it) }
        }
    }

    /** Eén bestandje per DNG-tegel; id en grootte zitten in de naam, dus een ander bestand onder dezelfde id leest een oud plaatje nooit. */
    private fun tileFile(item: MediaItem, edge: Int) = File(File(context.cacheDir, "tiles"), "${item.id}-${item.size}-$edge.webp")

    private fun storedTile(item: MediaItem, edge: Int): Bitmap? =
        tileFile(item, edge).takeIf { it.isFile }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }

    @Suppress("DEPRECATION")
    private fun storeTile(item: MediaItem, edge: Int, bitmap: Bitmap) {
        runCatching {
            val file = tileFile(item, edge)
            file.parentFile?.mkdirs()
            // Eerst naar een tijdelijk bestand, zodat een onderbroken schrijfbeurt nooit een half plaatje achterlaat.
            val temp = File(file.path + ".tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 85, it) }
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    /** Android's eigen miniatuur. Stopt zodra de aanroep geannuleerd wordt, dus een tegel die al uit beeld is kost niets meer. */
    private suspend fun system(item: MediaItem, edge: Int): Bitmap? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        // Wordt aangeroepen door wie annuleert, dus ook terwijl deze thread nog in loadThumbnail zit.
        cont.invokeOnCancellation { signal.cancel() }
        cont.resume(runCatching { context.contentResolver.loadThumbnail(imageUri(item.id), Size(edge, edge), signal) }.getOrNull())
    }

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
    suspend fun detail(item: MediaItem, maxEdge: Int): Bitmap? = withContext(decoders) {
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

    /** Beeld maken (miniaturen en het grote beeld) loopt via een paar threads, niet via alle 64 van Dispatchers.IO. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoders = Dispatchers.IO.limitedParallelism(3)

    companion object {
        private val PROJECTION = arrayOf(
            "_id", C.DISPLAY_NAME, C.RELATIVE_PATH, C.SIZE, C.DATE_ADDED, C.DATE_TAKEN, C.WIDTH, C.HEIGHT, C.MIME_TYPE, C.IS_FAVORITE,
        )

        fun imageUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }
}
