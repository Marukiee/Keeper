package nl.markmaaktmedia.keeper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import nl.markmaaktmedia.keeper.convert.ConvertRepository
import nl.markmaaktmedia.keeper.convert.OriginalsHider
import nl.markmaaktmedia.keeper.data.KeeperPrefs
import nl.markmaaktmedia.keeper.scan.MediaScanner
import nl.markmaaktmedia.markupdate.MarkUpdate
import nl.markmaaktmedia.markupdate.MarkUpdateConfig

/** De losse onderdelen van de app, op één plek, zoals in Tandem. */
class Graph(context: Context) {
    val prefs = KeeperPrefs(context)
    // De gedeelde updater (module markupdate). Hier opgezet, want de ontvanger van de installer gebruikt hetzelfde exemplaar.
    val updater = MarkUpdate.init(
        context,
        MarkUpdateConfig(
            owner = BuildConfig.GITHUB_OWNER, repo = BuildConfig.GITHUB_REPO, apkAsset = "Keeper.apk",
            currentVersion = BuildConfig.VERSION_NAME, appName = "Keeper", notificationIcon = R.drawable.ic_stat_keeper,
        ),
    )
    val scanner = MediaScanner(context, prefs)
    val converter = ConvertRepository(context.applicationContext, prefs, scanner)
    val hider = OriginalsHider(context.applicationContext, prefs)
}

class KeeperApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Channels.create(this)
        AppLanguage.defaultToEnglishOnce(this)
    }
}

val Context.graph: Graph
    get() = (applicationContext as KeeperApp).graph

object Channels {
    /** De voortgang van het omzetten (stil) en het verslag aan het eind (met geluid). */
    const val CONVERT = "convert"
    const val CONVERT_DONE = "convert_done"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CONVERT, context.getString(R.string.channel_convert), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_convert_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CONVERT_DONE, context.getString(R.string.channel_convert_done), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_convert_done_desc) },
        )
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}
