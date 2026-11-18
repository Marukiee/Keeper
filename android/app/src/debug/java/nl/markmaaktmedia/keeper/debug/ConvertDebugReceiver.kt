package nl.markmaaktmedia.keeper.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.markmaaktmedia.keeper.convert.ConvertJob
import nl.markmaaktmedia.keeper.convert.ConvertOptions
import nl.markmaaktmedia.keeper.convert.ConvertSettings
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.convert.Converter
import nl.markmaaktmedia.keeper.convert.HdrPreview
import nl.markmaaktmedia.keeper.convert.hdrPreviewSupported
import nl.markmaaktmedia.keeper.convert.JpegSource
import nl.markmaaktmedia.keeper.convert.JpegSegments
import nl.markmaaktmedia.keeper.scan.ToneMap

/**
 * adb shell am broadcast -n nl.markmaaktmedia.keeper.debug/nl.markmaaktmedia.keeper.debug.ConvertDebugReceiver \
 *   -a nl.markmaaktmedia.keeper.CONVERT --es in foto.jpg --es out uit.jpg --ei q 92 --es tonemap normaal [--ei strength 50] [--ez keephdr true]
 * Bestanden staan in de eigen map van de app (filesDir); de uitslag staat in logcat onder KeeperConvert.
 *
 * Met `--es ids 123,456` draait in plaats daarvan de echte keten (`ConvertJob`: plan, lezen via MediaStore, uitvoer in MediaStore) op die
 * MediaStore-ids, naar `--es folder Pictures/KeeperTest/` (standaard die map, nooit de echte uitvoermap). `--ez original false` leest
 * zonder `setRequireOriginal`, voor als de locatie-toestemming ontbreekt; de uitvoer heeft dan geen GPS. `--ez keephdr true` (in beide
 * vormen) is HDR behouden; `--ez forcehide true` (bij `ids`) zet de originelen van die run klaar voor verbergen (zie `hideop`);
 * `--es hideop set|pending|verify|clear` test Originelen verbergen. HDR behouden: een Ultra HDR-bestand met de sterkte erin, in plaats van SDR; bij `ids` valt het anders terug op de instelling.
 */
class ConvertDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        intent.getStringExtra("ids")?.let { runJob(context, intent, it, pending); return }
        intent.getStringExtra("hideop")?.let { hideOp(context, it, intent, pending); return }
        intent.getStringExtra("inspect")?.let { inspect(context, it); pending.finish(); return }
        intent.getStringExtra("hdrpreview")?.let { hdrPreview(context, it, intent.getIntExtra("strength", 100).coerceIn(0, 100) / 100f); pending.finish(); return }
        val name = intent.getStringExtra("in") ?: return
        val outName = intent.getStringExtra("out") ?: "uit.jpg"
        val q = intent.getIntExtra("q", 92)
        val tone = ToneMap.of(intent.getStringExtra("tonemap") ?: "normaal")
        val chroma420 = intent.getStringExtra("chroma") != "444"
        val strength = intent.getIntExtra("strength", 100).coerceIn(0, 100) / 100f
        val keepHdr = intent.getBooleanExtra("keephdr", false)
        thread(name = "convert-debug") {
            try {
                val input = File(context.filesDir, name)
                val output = File(context.filesDir, outName)
                val res = BufferedOutputStream(output.outputStream(), 1 shl 16).use { out ->
                    val converter = Converter(log = { Log.i(TAG, it) })
                    val settings = ConvertSettings(q, tone, chroma420, strength, keepHdr)
                    val source = JpegSource { ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY) }
                    if (settings.keepsHdr) converter.keepHdr(source, out, settings, File(context.cacheDir, "keep-debug.tmp"))
                    else converter.convert(
                        source = source,
                        out = out,
                        settings = settings,
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

    /**
     * Originelen verbergen testen: `--es hideop set --ez on true` zet de instelling, `pending` toont wat klaarstaat, `verify` controleert dat zonder iets te
     * verbergen, `clear` leegt de lijst. Uitslag in logcat onder KeeperConvert (HIDEOP).
     */
    private fun hideOp(context: Context, op: String, intent: Intent, pending: PendingResult) {
        thread(name = "hide-debug") {
            try {
                val graph = context.applicationContext.graph
                runBlocking {
                    when (op) {
                        "set" -> { graph.prefs.setHideOriginals(intent.getBooleanExtra("on", false)); Log.i(TAG, "HIDEOP set ${intent.getBooleanExtra("on", false)}") }
                        "pending" -> Log.i(TAG, "HIDEOP pending ${graph.prefs.pendingHide.first()}")
                        "verify" -> graph.hider.verify().let { b -> Log.i(TAG, "HIDEOP verify ready=${b.ready.map { it.name }} dropped=${b.dropped} canManage=${graph.hider.canManageMedia}") }
                        "clear" -> { graph.prefs.removePendingHide(graph.prefs.pendingHide.first().mapNotNull { nl.markmaaktmedia.keeper.convert.HideEntry.decode(it)?.originalId }.toSet()); Log.i(TAG, "HIDEOP clear") }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "HIDEOP MISLUKT $op", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** `--es hdrpreview foto.jpg --ei strength 50`: het HDR-voorbeeld van de viewer (zie `HdrPreview`) en de gain map van wat het teruggeeft, in logcat. */
    private fun hdrPreview(context: Context, name: String, strength: Float) {
        try {
            if (!hdrPreviewSupported) { Log.i(TAG, "HDRPREVIEW niet ondersteund op dit toestel"); return }
            val t0 = System.nanoTime()
            val preview = HdrPreview.open(context.contentResolver, Uri.fromFile(File(context.filesDir, name))) ?: run { Log.i(TAG, "HDRPREVIEW $name: geen voorbeeld"); return }
            val t1 = System.nanoTime()
            val bitmap = preview.render(strength)
            val t2 = System.nanoTime()
            val gm = bitmap.gainmap
            fun f(v: FloatArray) = v.joinToString(",") { "%.4f".format(it) }
            Log.i(TAG, "HDRPREVIEW $name ${bitmap.width}x${bitmap.height} open ${(t1 - t0) / 1_000_000} ms, render ${(t2 - t1) / 1_000_000} ms, gainmap " +
                (if (gm == null) "ONTBREEKT" else "ratioMax=${f(gm.ratioMax)} ratioMin=${f(gm.ratioMin)} epsHdr=${f(gm.epsilonHdr)} fullHdr=${"%.4f".format(gm.displayRatioForFullHdr)}"))
        } catch (t: Throwable) {
            Log.e(TAG, "HDRPREVIEW MISLUKT $name", t)
        }
    }

    /** `--es inspect uit.jpg`: wat Android zelf van het bestand maakt (ImageDecoder, gain map en zijn waarden), in logcat onder KeeperConvert. */
    private fun inspect(context: Context, name: String) {
        try {
            val file = File(context.filesDir, name)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            val gm = bitmap.gainmap
            if (gm == null) Log.i(TAG, "INSPECT $name ${bitmap.width}x${bitmap.height} geen gain map, ruimte ${bitmap.colorSpace?.name}")
            else {
                fun f(v: FloatArray) = v.joinToString(",") { "%.4f".format(it) }
                Log.i(TAG, "INSPECT $name ${bitmap.width}x${bitmap.height} ruimte ${bitmap.colorSpace?.name} gainmap ${gm.gainmapContents.width}x${gm.gainmapContents.height} " +
                    "ratioMin=${f(gm.ratioMin)} ratioMax=${f(gm.ratioMax)} gamma=${f(gm.gamma)} epsSdr=${f(gm.epsilonSdr)} epsHdr=${f(gm.epsilonHdr)} " +
                    "fullHdr=${"%.4f".format(gm.displayRatioForFullHdr)} minHdr=${"%.4f".format(gm.minDisplayRatioForHdrTransition)}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "INSPECT MISLUKT $name", t)
        }
    }

    private fun runJob(context: Context, intent: Intent, ids: String, pending: PendingResult) {
        val only = ids.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        val folder = intent.getStringExtra("folder") ?: "Pictures/KeeperTest/"
        val original = intent.getBooleanExtra("original", true)
        val keepHdr = if (intent.hasExtra("keephdr")) intent.getBooleanExtra("keephdr", false) else null
        val forceHide = intent.getBooleanExtra("forcehide", false)
        thread(name = "convert-job-debug") {
            try {
                val graph = context.applicationContext.graph
                val report = runBlocking {
                    ConvertJob(context.applicationContext, graph.prefs, graph.scanner).run(
                        { Log.i(TAG, "voortgang ${it.index}/${it.total} ${it.name} ${(it.fraction * 100).toInt()}%") }, { false },
                        ConvertOptions(only, folder, original, keepHdr = keepHdr, forceHide = forceHide),
                    )
                }
                Log.i(TAG, "JOB converted=${report.converted} copied=${report.copied} skipped=${report.skipped} paused=${report.paused} left=${report.left} problem=${report.problem} in=${report.bytesIn} out=${report.bytesOut}")
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
