package nl.markmaaktmedia.keeper.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.keeper.convert.ConvertJob
import nl.markmaaktmedia.keeper.convert.ConvertOptions
import nl.markmaaktmedia.keeper.convert.ConvertSettings
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.convert.Converter
import nl.markmaaktmedia.keeper.convert.JpegSegments
import nl.markmaaktmedia.keeper.scan.ToneMap

/**
 * adb shell am broadcast -n nl.markmaaktmedia.keeper.debug/nl.markmaaktmedia.keeper.debug.ConvertDebugReceiver \
 *   -a nl.markmaaktmedia.keeper.CONVERT --es in foto.jpg --es out uit.jpg --ei q 92 --es tonemap normaal
 * Bestanden staan in de eigen map van de app (filesDir); de uitslag staat in logcat onder KeeperConvert.
 *
 * Met `--es ids 123,456` draait in plaats daarvan de echte keten (`ConvertJob`: plan, lezen via MediaStore, uitvoer in MediaStore) op die
 * MediaStore-ids, naar `--es folder Pictures/KeeperTest/` (standaard die map, nooit de echte uitvoermap). `--ez original false` leest
 * zonder `setRequireOriginal`, voor als de locatie-toestemming ontbreekt; de uitvoer heeft dan geen GPS.
 */
class ConvertDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        intent.getStringExtra("ids")?.let { runJob(context, intent, it, pending); return }
        val name = intent.getStringExtra("in") ?: return
        val outName = intent.getStringExtra("out") ?: "uit.jpg"
        val q = intent.getIntExtra("q", 92)
        val tone = ToneMap.of(intent.getStringExtra("tonemap") ?: "normaal")
        val chroma420 = intent.getStringExtra("chroma") != "444"
        thread(name = "convert-debug") {
            try {
                val input = File(context.filesDir, name)
                val output = File(context.filesDir, outName)
                val res = BufferedOutputStream(output.outputStream(), 1 shl 16).use { out ->
                    Converter(log = { Log.i(TAG, it) }).convert(
                        source = { ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY) },
                        out = out,
                        settings = ConvertSettings(q, tone, chroma420),
                        segmentsFor = { head, _, _ -> head.icc()?.let { JpegSegments.icc(it) } ?: emptyList() },
                        onProgress = {},
                    )
                }
                Log.i(TAG, "KLAAR ${res.width}x${res.height} piek=${res.peak} gain=${res.gainMapSize} ruimte=${res.colorSpace} ${res.millis} ms, uitvoer ${output.length()} bytes")
            } catch (t: Throwable) {
                Log.e(TAG, "MISLUKT", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun runJob(context: Context, intent: Intent, ids: String, pending: PendingResult) {
        val only = ids.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        val folder = intent.getStringExtra("folder") ?: "Pictures/KeeperTest/"
        val original = intent.getBooleanExtra("original", true)
        thread(name = "convert-job-debug") {
            try {
                val graph = context.applicationContext.graph
                val report = runBlocking {
                    ConvertJob(context.applicationContext, graph.prefs, graph.scanner).run(
                        { Log.i(TAG, "voortgang ${it.index}/${it.total} ${it.name} ${(it.fraction * 100).toInt()}%") }, { false },
                        ConvertOptions(only, folder, original),
                    )
                }
                Log.i(TAG, "JOB converted=${report.converted} copied=${report.copied} skipped=${report.skipped} cancelled=${report.cancelled} problem=${report.problem} in=${report.bytesIn} out=${report.bytesOut}")
                report.failures.forEach { Log.i(TAG, "JOB MISLUKT ${it.name}: ${it.reason}") }
            } catch (t: Throwable) {
                Log.e(TAG, "JOB MISLUKT", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "KeeperConvert" }
}
