package nl.markmaaktmedia.keeper.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import kotlin.concurrent.thread
import nl.markmaaktmedia.keeper.convert.ConvertSettings
import nl.markmaaktmedia.keeper.convert.Converter
import nl.markmaaktmedia.keeper.convert.JpegSegments
import nl.markmaaktmedia.keeper.scan.ToneMap

/**
 * adb shell am broadcast -n nl.markmaaktmedia.keeper.debug/nl.markmaaktmedia.keeper.debug.ConvertDebugReceiver \
 *   -a nl.markmaaktmedia.keeper.CONVERT --es in foto.jpg --es out uit.jpg --ei q 92 --es tonemap normaal
 * Bestanden staan in de eigen map van de app (filesDir); de uitslag staat in logcat onder KeeperConvert.
 */
class ConvertDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
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

    private companion object { const val TAG = "KeeperConvert" }
}
