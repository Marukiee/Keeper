package nl.markmaaktmedia.keeper.convert

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.Channels
import nl.markmaaktmedia.keeper.MainActivity
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.ui.formatBytes

/**
 * Houdt de app wakker terwijl er wordt omgezet: een voorgrondservice (dataSync) met een melding die de voortgang toont en een
 * Stop-knop heeft. Alle werk zit in [ConvertRepository]; dit is alleen de schil eromheen en de melding aan het eind.
 */
class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = applicationContext.graph.converter
        if (intent?.action == ACTION_STOP) {
            repository.requestStop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, progressNotification(ConvertState.Running()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Hooguit een uur wakker: een run die langer duurt, houdt dan de CPU niet meer vast maar loopt door zolang de service leeft.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keeper:convert").apply { acquire(60 * 60 * 1000L) }
        }
        repository.begin()
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                repository.state.collect { state ->
                    when (state) {
                        is ConvertState.Running -> {
                            NotificationManagerCompat.from(this@ConvertService).notifyIfAllowed(NOTIFICATION_ID, progressNotification(state))
                            // Meldingen mogen niet vaker dan een paar keer per seconde; de laatste stand komt na de pauze alsnog.
                            delay(600)
                        }
                        is ConvertState.Finished -> {
                            finish(state.report)
                            return@collect
                        }
                        ConvertState.Idle -> Unit
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(report: ConvertReport) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).notifyIfAllowed(DONE_ID, doneNotification(report))
        releaseWakeLock()
        stopSelf()
    }

    /** Android 15 geeft een dataSync-service een tijdgrens; daarna stoppen we netjes in plaats van gedood te worden. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        applicationContext.graph.converter.requestStop()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun progressNotification(state: ConvertState.Running): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ConvertService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, Channels.CONVERT)
            .setSmallIcon(R.drawable.ic_stat_keeper)
            .setContentTitle(getString(R.string.convert_notification_title))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.convert_stop), stop)
        if (state.total > 0) {
            builder.setContentText(getString(R.string.convert_progress, state.index, state.total, state.name))
            builder.setProgress(1000, (((state.index - 1) + state.fraction) / state.total * 1000).toInt().coerceIn(0, 1000), false)
        } else {
            builder.setContentText(getString(R.string.convert_preparing))
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun doneNotification(report: ConvertReport): Notification = NotificationCompat.Builder(this, Channels.CONVERT_DONE)
        .setSmallIcon(R.drawable.ic_stat_keeper)
        .setContentTitle(getString(if (report.cancelled) R.string.convert_stopped else if (report.failures.isEmpty()) R.string.convert_done else R.string.convert_done_with_problems))
        .setContentText(summaryLine(this, report))
        .setContentIntent(openApp())
        .setAutoCancel(true)
        .build()

    companion object {
        private const val NOTIFICATION_ID = 21
        private const val DONE_ID = 22
        private const val ACTION_STOP = "nl.markmaaktmedia.keeper.convert.STOP"

        /** Eén regel over wat er gebeurd is, voor de melding en het verslag. */
        fun summaryLine(context: Context, report: ConvertReport): String = buildString {
            append(context.resources.getQuantityString(R.plurals.convert_photos_done, report.done, report.done))
            if (report.savedBytes > 0) append(", ").append(context.getString(R.string.convert_saved, formatBytes(report.savedBytes)))
            if (report.failures.isNotEmpty()) append(", ").append(context.getString(R.string.convert_failed_count, report.failures.size))
        }

        private fun NotificationManagerCompat.notifyIfAllowed(id: Int, notification: Notification) {
            if (areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                notify(id, notification)
            }
        }
    }
}
