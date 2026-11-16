package nl.markmaaktmedia.keeper.ui

import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale

// De taal van de app, op het moment van gebruiken, zodat een andere taal meteen meegaat.
private val nl: Locale get() = Locale.getDefault()

/** 1,2 GB, 340 MB: decimale eenheden zoals Android zelf ze toont. */
fun formatBytes(bytes: Long): String {
    val symbols = DecimalFormatSymbols(nl)
    return when {
        bytes >= 1_000_000_000 -> DecimalFormat("0.0", symbols).format(bytes / 1e9) + " GB"
        bytes >= 1_000_000 -> DecimalFormat("0", symbols).format(bytes / 1e6) + " MB"
        else -> DecimalFormat("0", symbols).format(bytes / 1e3) + " kB"
    }
}

fun formatMegapixels(mp: Double): String = DecimalFormat("0.#", DecimalFormatSymbols(nl)).format(mp) + " MP"

fun formatDay(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, nl).format(Date(ms))

fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, nl).format(Date(ms))
