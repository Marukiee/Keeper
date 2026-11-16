package nl.markmaaktmedia.daglicht

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import nl.markmaaktmedia.daglicht.data.DaglichtPrefs
import nl.markmaaktmedia.daglicht.scan.MediaScanner
import nl.markmaaktmedia.daglicht.update.UpdateRepository

/** De losse onderdelen van de app, op één plek, zoals in Tandem. */
class Graph(context: Context) {
    val prefs = DaglichtPrefs(context)
    val updater = UpdateRepository(context, prefs)
    val scanner = MediaScanner(context, prefs)
}

class DaglichtApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Channels.create(this)
    }
}

val Context.graph: Graph
    get() = (applicationContext as DaglichtApp).graph

object Channels {
    const val UPDATE = "update"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(UPDATE, context.getString(R.string.channel_update), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_update_desc) },
        )
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
