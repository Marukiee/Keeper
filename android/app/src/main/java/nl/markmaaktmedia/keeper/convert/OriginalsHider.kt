package nl.markmaaktmedia.keeper.convert

import android.content.Context
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.markmaaktmedia.keeper.data.KeeperPrefs
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.keeper.scan.OutputName

/**
 * Het origineel uit de feed halen zodra de uitvoer er is ("Originelen verbergen"). Keeper wist hier nooit zelf iets: het origineel gaat naar de
 * prullenbak van MediaStore (`IS_TRASHED`). Daar zien de Galerij en andere apps het niet meer, kan Mark het terugzetten, en haalt Android het zelf weg
 * na de bewaartermijn van de prullenbak (normaal 30 dagen; zie `DATE_EXPIRES`).
 *
 * Wat verborgen mag worden zet [ConvertJob] klaar als [HideEntry] (alleen camera-JPG's uit DCIM die in een hele run zijn weggeschreven, zie
 * [OriginalsHiding.eligible]). Dit controleert elk ervan opnieuw vlak voor het verbergen, want tussen de omzetting en nu kan er van alles gebeurd zijn:
 * de uitvoer moet er nog staan (niet meer bezig, even groot als geschreven, te lezen op dezelfde foto als het origineel (zelfde verhouding, minstens 40% van de pixels: Resolutie Automatisch halveert), en bij HDR behouden nog een
 * Ultra HDR-bestand), en het origineel moet nog hetzelfde bestand zijn. Wat niet klopt, wordt niet verborgen en verdwijnt van de lijst.
 *
 * Verbergen gaat via één verzoek aan Android voor het hele pakket ([request]); dat moet vanuit een scherm gestart worden, dus dat doet de app, niet de
 * omzetservice. Android vraagt daarbij om een bevestiging, behalve als Keeper mediabeheer heeft ([canManageMedia], speciale toegang die Mark zelf in de
 * systeeminstellingen aanzet): dan keurt het systeem het verzoek zelf goed. Een directe `update` van `IS_TRASHED` mag ook mét mediabeheer niet op
 * bestanden van andere apps (op de emulator: "has no access"), dus dat wordt niet geprobeerd.
 */
class OriginalsHider(private val context: Context, private val prefs: KeeperPrefs) {
    private val resolver = context.contentResolver

    /** Wat klaar is om te verbergen ([ready]) en hoeveel regels er bij de controle afvielen ([dropped]). */
    class Batch(val ready: List<HideEntry>, val dropped: Int)

    /** Mag de app media van anderen verbergen of verwijderen zonder te vragen (speciale toegang "Mediabeheer")? */
    val canManageMedia: Boolean get() = MediaStore.canManageMedia(context)

    /** Controleert wat er klaarstaat; wat niet klopt gaat van de lijst af. */
    suspend fun verify(): Batch = verify(prefs.pendingHide.first().mapNotNull(HideEntry::decode))

    /** Controleert [entries] (de lijst van de instelling, of van [collectDone]); wat niet klopt gaat van de lijst af. */
    suspend fun verify(entries: List<HideEntry>): Batch = withContext(Dispatchers.IO) {
        val ready = ArrayList<HideEntry>()
        val drop = HashSet<Long>()
        for (e in entries) if (check(e)) ready += e else drop += e.originalId
        if (drop.isNotEmpty()) prefs.removePendingHide(drop)
        Batch(ready, drop.size)
    }

    /**
     * Voor de knop "Originelen opruimen": van elke foto die al klaar is ([OriginalsHiding.eligibleDone]) de uitvoer in de uitvoermap erbij zoeken (de
     * nieuwste als er meer zijn, bijvoorbeeld een -SDR en een -HDR), zodat [verify] ze net zo kan controleren als wat een run klaarzet. Foto's zonder
     * uitvoer in MediaStore vallen hier al af.
     */
    suspend fun collectDone(photos: List<nl.markmaaktmedia.keeper.scan.Photo>): List<HideEntry> = withContext(Dispatchers.IO) {
        val eligible = photos.filter(OriginalsHiding::eligibleDone)
        if (eligible.isEmpty()) return@withContext emptyList()
        val output = prefs.outputFolder.first()
        val lightroom = prefs.lightroomFolder.first()
        val outputs = HashMap<String, Pair<Long, Long>>()
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$output%"), null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1)?.lowercase() ?: continue
                val id = cur.getLong(0)
                if ((outputs[name]?.first ?: -1L) < id) outputs[name] = id to cur.getLong(2)
            }
        }
        eligible.mapNotNull { p ->
            val (name, found) = OutputName.candidates(p.item, lightroom).mapNotNull { n -> outputs[n.lowercase()]?.let { n to it } }.maxByOrNull { it.second.first } ?: return@mapNotNull null
            val hdr = name.substringBeforeLast('.').endsWith(OutputName.Tag.Hdr.suffix, ignoreCase = true)
            HideEntry(p.item.id, p.item.name, p.item.size, found.first, found.second, hdr)
        }
    }

    /** De bevestiging van Android voor het verbergen van [entries] in één keer; start hem met een `StartIntentSenderForResult`. */
    fun request(entries: List<HideEntry>): IntentSender =
        MediaStore.createTrashRequest(resolver, entries.map { MediaScanner.imageUri(it.originalId) }, true).intentSender

    /** Deze zijn verborgen (of Mark wilde het niet): van de lijst af. */
    suspend fun finished(entries: List<HideEntry>) {
        if (entries.isNotEmpty()) prefs.removePendingHide(entries.map { it.originalId }.toSet())
    }

    // ---- controle ----

    private class Row(val name: String, val size: Long, val path: String, val width: Int, val height: Int, val favorite: Boolean)

    private fun row(id: Long): Row? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.IS_FAVORITE,
        )
        return resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.MediaColumns._ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            if (!c.moveToFirst()) null else Row(c.getString(0) ?: "", c.getLong(1), c.getString(2) ?: "", c.getInt(3), c.getInt(4), c.getInt(5) == 1)
        }
    }

    private fun check(e: HideEntry): Boolean = try {
        val original = row(e.originalId)
        // Het origineel is hetzelfde bestand als bij de omzetting, nog uit DCIM, en geen favoriet geworden.
        val originalOk = original != null && original.name == e.name && original.size == e.originalSize &&
            original.path.startsWith("DCIM/") && !original.favorite
        // De uitvoer is er (rijen die nog bezig zijn komen niet terug uit deze vraag), even groot als geschreven, en niet leeg.
        val output = row(e.outputId)
        val outputOk = output != null && output.size > 0 && output.size == e.outputSize
        originalOk && outputOk && readable(e, original!!) && (!e.hdr || hasGainMap(e.outputId))
    } catch (_: Exception) {
        false
    }

    /** De uitvoer is te openen en te decoderen (de kop), en dezelfde foto als het origineel (ook op de helft van de pixels, zie [OriginalsHiding.outputMatches]). */
    private fun readable(e: HideEntry, original: Row): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val pfd = resolver.openFileDescriptor(MediaScanner.imageUri(e.outputId), "r") ?: return false
        pfd.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts) }
        return OriginalsHiding.outputMatches(opts.outWidth, opts.outHeight, original.width, original.height)
    }

    private fun hasGainMap(id: Long): Boolean =
        UltraHdrReader.read {
            ParcelFileDescriptor.AutoCloseInputStream(resolver.openFileDescriptor(MediaScanner.imageUri(id), "r") ?: throw java.io.IOException("niet te openen"))
        } != null
}
