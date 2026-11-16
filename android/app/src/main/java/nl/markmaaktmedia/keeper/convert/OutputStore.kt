package nl.markmaaktmedia.keeper.convert

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import java.io.IOException

/**
 * De uitvoermap in MediaStore. Een bestand wordt eerst als "pending" aangemaakt (onzichtbaar voor andere apps, ook voor Immich), en pas
 * zichtbaar als het af is, dus een onderbroken run laat nooit een half plaatje achter in de map die gesynchroniseerd wordt.
 * Alleen wat Keeper zelf aanmaakte wordt ooit verwijderd.
 */
class OutputStore(private val resolver: ContentResolver) {
    private val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Staat deze naam er al? MediaStore maakt bij een dubbele naam stilletjes "naam (1).jpg", dus dat vragen we eerst. */
    fun exists(folder: String, name: String): Boolean {
        resolver.query(files, arrayOf(C._ID), "${C.DISPLAY_NAME} = ? AND ${C.RELATIVE_PATH} = ?", arrayOf(name, folder), null)?.use { return it.moveToFirst() }
        return false
    }

    /** Maakt het bestand aan als pending. Schrijf er naartoe en roep dan [finish] aan, of [abort] als het mislukt. */
    fun create(folder: String, name: String, mime: String, takenMs: Long): Uri {
        val values = ContentValues().apply {
            put(C.DISPLAY_NAME, name)
            put(C.MIME_TYPE, mime)
            put(C.RELATIVE_PATH, folder)
            put(C.IS_PENDING, 1)
            if (takenMs > 0) put(C.DATE_TAKEN, takenMs)
        }
        return resolver.insert(images, values) ?: throw IOException("MediaStore gaf geen bestand voor $name")
    }

    fun finish(uri: Uri) {
        val done = resolver.update(uri, ContentValues().apply { put(C.IS_PENDING, 0) }, null, null)
        if (done < 1) throw IOException("het bestand kon niet worden afgemaakt")
    }

    /** Haalt het halve bestand weg dat deze run zelf aanmaakte. */
    fun abort(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /**
     * Pending bestanden van een vorige run die midden in het schrijven werd afgebroken (de app is gestopt). Andere apps zien onze pending
     * bestanden niet en wij de hunne niet, dus dit raakt alleen wat Keeper zelf achterliet.
     */
    fun removeLeftovers(folder: String): Int {
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${C.RELATIVE_PATH} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        }
        val ids = ArrayList<Long>()
        resolver.query(files, arrayOf(C._ID), query, null)?.use { while (it.moveToNext()) ids += it.getLong(0) }
        ids.forEach { runCatching { resolver.delete(ContentUris.withAppendedId(images, it), null, null) } }
        return ids.size
    }

    companion object {
        /** De map uit de instellingen als RELATIVE_PATH: zonder slash vooraan en met één erachter. */
        fun folder(setting: String): String = setting.trim().trim('/').let { if (it.isEmpty()) "Pictures/Keeper/" else "$it/" }
    }
}
